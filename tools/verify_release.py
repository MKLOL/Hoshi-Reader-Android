#!/usr/bin/env python3
"""Run the mandatory release gate. Requires the build toolchain and an API 36 emulator image.

Runs all JVM tests against the local HTTP KV simulator, lint/build checks, then the
critical Android regressions on a newly created disposable emulator. Never installs
on an existing device. Logs and the summary are in build/reports/release-gate/.
Set HOSHI_TEST_SYSTEM_IMAGE to choose another installed system image if necessary.

    python3 tools/verify_release.py                   the full gate
    python3 tools/verify_release.py --smoke-apk APK   launch the exact release APK that
                                                       will be published on another fresh
                                                       disposable emulator (release.py runs
                                                       this after building it). It catches
                                                       startup and first-screen breakage
                                                       (R8/resource shrinking on the launch
                                                       path), not code the launch never runs.
"""
from __future__ import annotations

from contextlib import contextmanager
import json
import os
from pathlib import Path
import platform
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import uuid

REPO = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO))
from release import KEYSTORE_ENV, resolve_ndk, sdk_dir
from tools.release_artifacts import apk_identity, digest
from tools.release_checks import (ANDROID_CLASSES, apk_native_abis, app_has_focus, check_device_abi,
                                  check_instrumentation, check_junit, check_launch, check_smoke,
                                  launcher_component)

REPORTS = REPO / "build/reports/release-gate"
BOOT_SECONDS = 240
# Long enough for first-launch setup (dictionary/database init, sync hooks) to crash or ANR.
SMOKE_SECONDS = 20
# Seconds to wait for the launched activity to take focus: longer when `am start -W` timed out.
FOCUS_SECONDS = 15
SLOW_FOCUS_SECONDS = 60
# Key presses make a hung main thread miss input dispatch, which Android reports as an ANR.
SMOKE_KEYS = ("KEYCODE_DPAD_DOWN", "KEYCODE_DPAD_UP")


def run(command: list[str], *, env: dict[str, str], log: Path, timeout: int = 3600,
        input_text: str | None = None) -> str:
    print(f"Running {command[0]} {' '.join(command[1:])} (log: {log})", flush=True)
    with log.open("w") as output:
        try:
            subprocess.run(command, cwd=REPO, env=env, input=input_text, text=True,
                           stdout=output, stderr=subprocess.STDOUT, check=True, timeout=timeout)
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
            print(log.read_text(errors="replace")[-12000:], file=sys.stderr)
            raise
    return log.read_text(errors="replace")


def free_emulator_port() -> int:
    # Console and adb ports form a pair. The AVD-name check below also guards a bind race.
    for port in range(5560, 5682, 2):
        with socket.socket() as console, socket.socket() as adb:
            try:
                console.bind(("127.0.0.1", port))
                adb.bind(("127.0.0.1", port + 1))
            except OSError:
                continue
            return port
    raise RuntimeError("No free emulator port pair")


@contextmanager
def disposable_emulator(env: dict[str, str], label: str = ""):
    """Boots a new AVD in a private directory and yields (adb, serial) only after proving its identity."""
    prefix = f"{label}-" if label else ""
    sdk = sdk_dir()
    adb = str(sdk / "platform-tools/adb")
    avdmanager = str(sdk / "cmdline-tools/latest/bin/avdmanager")
    emulator = str(sdk / "emulator/emulator")
    abi = "arm64-v8a" if platform.machine().lower() in ("arm64", "aarch64") else "x86_64"
    system_image = env.get("HOSHI_TEST_SYSTEM_IMAGE", f"system-images;android-36;google_apis;{abi}")
    image_path = sdk.joinpath(*system_image.split(";"))
    if not image_path.is_dir():
        raise RuntimeError(f"Missing emulator image. Install with sdkmanager '{system_image}'")
    name = "hoshi-release-" + uuid.uuid4().hex
    port = free_emulator_port()
    serial = f"emulator-{port}"
    with tempfile.TemporaryDirectory(prefix="hoshi-release-avd-") as avd_home:
        # Both registration and disk image live in this newly owned temporary directory.
        device_env = dict(env, ANDROID_AVD_HOME=avd_home)
        run([avdmanager, "create", "avd", "--name", name, "--package", system_image,
             "--device", "pixel_7"], env=device_env, log=REPORTS / f"{prefix}avd-create.log",
            timeout=120, input_text="no\n")
        with (REPORTS / f"{prefix}emulator.log").open("w") as output:
            process = subprocess.Popen(
                [emulator, "-avd", name, "-port", str(port), "-no-window", "-no-audio",
                 "-no-snapshot", "-no-boot-anim", "-gpu", "swiftshader_indirect"],
                env=device_env, stdout=output, stderr=subprocess.STDOUT,
            )
            verified = False
            try:
                deadline = time.monotonic() + BOOT_SECONDS
                while time.monotonic() < deadline:
                    if process.poll() is not None:
                        raise RuntimeError(f"Emulator exited during boot; see {prefix}emulator.log")
                    state = subprocess.run([adb, "-s", serial, "shell", "getprop", "sys.boot_completed"],
                                           capture_output=True, text=True, timeout=15)
                    if state.returncode == 0 and state.stdout.strip() == "1":
                        break
                    time.sleep(1)
                else:
                    raise RuntimeError(f"Disposable emulator did not boot within {BOOT_SECONDS} seconds")
                identity = subprocess.run([adb, "-s", serial, "emu", "avd", "name"],
                                          capture_output=True, text=True, check=True, timeout=15).stdout
                if identity.splitlines()[0].strip() != name:
                    raise RuntimeError("Refusing to install: device is not this gate's disposable AVD")
                verified = True
                for setting in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
                    run([adb, "-s", serial, "shell", "settings", "put", "global", setting, "0"],
                        env=env, log=REPORTS / f"{prefix}{setting}.log", timeout=20)
                yield adb, serial
            finally:
                if verified:
                    for filename, args in ((f"{prefix}logcat.txt", ["logcat", "-d"]),
                                           (f"{prefix}emulator-stop.log", ["emu", "kill"])):
                        try:
                            run([adb, "-s", serial, *args], env=env, log=REPORTS / filename, timeout=20)
                        except (OSError, subprocess.SubprocessError):
                            pass
                if process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=20)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=10)


def run_android(env: dict[str, str]) -> int:
    with disposable_emulator(env) as (adb, serial):
        for label, apk in (
            ("app", "app/build/outputs/apk/debug/app-debug.apk"),
            ("tests", "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"),
        ):
            run([adb, "-s", serial, "install", str(REPO / apk)], env=env,
                log=REPORTS / f"install-{label}.log", timeout=120)
        result = run(
            [adb, "-s", serial, "shell", "am", "instrument", "-w", "-r", "-e", "class",
             ",".join(ANDROID_CLASSES), "moe.antimony.hoshi.debug.test/androidx.test.runner.AndroidJUnitRunner"],
            env=env, log=REPORTS / "android-tests.log", timeout=1200,
        )
        return check_instrumentation(result)


def adb_output(adb: str, serial: str, *args: str) -> str:
    """For probes whose non-zero exit is an answer (pidof with no process), not a failure."""
    return subprocess.run([adb, "-s", serial, *args], capture_output=True, text=True, timeout=30).stdout


def wait_for_focus(adb: str, serial: str, package: str, seconds: int) -> None:
    for _ in range(seconds):
        if app_has_focus(adb_output(adb, serial, "shell", "dumpsys", "window"), package):
            return
        time.sleep(1)
    raise ValueError(f"Release APK activity never held focus within {seconds} seconds of launch "
                     "(still starting, hung, or replaced by a crash/ANR dialog)")


def smoke_release_apk(apk: Path, env: dict[str, str]) -> dict[str, object]:
    """Installs and launches the exact minified release APK, then sends input and watches it.

    This catches startup and first-screen breakage that only the shipped build has (R8 or
    resource shrinking on the launch path). Code the launch never reaches is not exercised.
    """
    REPORTS.mkdir(parents=True, exist_ok=True)
    receipt = REPORTS / "release-smoke.json"
    receipt.unlink(missing_ok=True)
    package, version_code, version_name, _ = apk_identity(apk)
    apk_abis = apk_native_abis(apk)
    sha256 = digest(apk)
    with disposable_emulator(env, label="release-smoke") as (adb, serial):
        check_device_abi(apk_abis, adb_output(adb, serial, "shell", "getprop", "ro.product.cpu.abilist"))
        run([adb, "-s", serial, "install", "-r", str(apk)], env=env,
            log=REPORTS / "release-smoke-install.log", timeout=180)
        component = launcher_component(
            run([adb, "-s", serial, "shell", "cmd", "package", "resolve-activity", "--brief",
                 "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", package],
                env=env, log=REPORTS / "release-smoke-resolve.log", timeout=30),
            package,
        )
        run([adb, "-s", serial, "logcat", "-c"], env=env, log=REPORTS / "release-smoke-logcat-clear.log", timeout=30)
        launched = check_launch(run([adb, "-s", serial, "shell", "am", "start", "-W", "-n", component],
                                    env=env, log=REPORTS / "release-smoke-launch.log", timeout=120))
        pid_at_launch = adb_output(adb, serial, "shell", "pidof", package)
        wait_for_focus(adb, serial, package, FOCUS_SECONDS if launched else SLOW_FOCUS_SECONDS)
        for key in SMOKE_KEYS:
            run([adb, "-s", serial, "shell", "input", "keyevent", key], env=env,
                log=REPORTS / "release-smoke-input.log", timeout=30)
            time.sleep(SMOKE_SECONDS / len(SMOKE_KEYS))
        pid_after_wait = adb_output(adb, serial, "shell", "pidof", package)
        logcat = run([adb, "-s", serial, "logcat", "-d"], env=env,
                     log=REPORTS / "release-smoke-app-logcat.txt", timeout=60)
        check_smoke(package, pid_at_launch, pid_after_wait, logcat)
        if not app_has_focus(adb_output(adb, serial, "shell", "dumpsys", "window"), package):
            raise ValueError("Release APK lost focus after input (hung, crashed, or showing an error dialog)")
    result = {"apk_sha256": sha256, "package": package, "version_code": version_code,
              "version_name": version_name, "abis": sorted(apk_abis), "seconds_alive": SMOKE_SECONDS}
    receipt.write_text(json.dumps(result, indent=2) + "\n")
    return result


def verify() -> None:
    REPORTS.mkdir(parents=True, exist_ok=True)
    summary = REPORTS / "summary.json"
    summary.unlink(missing_ok=True)
    env = dict(os.environ)
    env["ANDROID_NDK_HOME"] = resolve_ndk()
    env["HOSHI_PYTHON"] = sys.executable
    env["HOSHI_ENABLE_LIVE_SYNC_TESTS"] = "0"
    # This gate is self-contained. External production credentials are never used by tests.
    for key in tuple(env):
        if key.startswith("HOSHI_KV_") or key in KEYSTORE_ENV:
            env.pop(key)
    run([sys.executable, "-m", "unittest", "discover", "-s", "tools/release_tests", "-v"],
        env=env, log=REPORTS / "gate-tests.log", timeout=120)
    run([sys.executable, "tools/sync-test-server/test_sync_test_server.py"],
        env=env, log=REPORTS / "kv-contract.log", timeout=120)
    results = REPO / "app/build/test-results/testDebugUnitTest"
    # Do not accept stale or filtered reports from a previous developer invocation.
    if results.exists():
        shutil.rmtree(results)
    run(["./gradlew", "--no-daemon", "test", "lint", "assembleDebug", "assembleDebugAndroidTest"],
        env=env, log=REPORTS / "gradle.log")
    jvm = check_junit(results)
    print(f"JVM gate passed: {jvm}", flush=True)
    android = run_android(env)
    summary.write_text(json.dumps({"jvm": jvm, "android_passed": android}, indent=2) + "\n")
    print(f"Release gate passed: {jvm['passed']} JVM tests, {android} Android tests. Reports: {REPORTS}", flush=True)


def smoke_environment() -> dict[str, str]:
    env = dict(os.environ)
    for key in tuple(env):
        if key.startswith("HOSHI_KV_") or key in KEYSTORE_ENV:
            env.pop(key)
    return env


if __name__ == "__main__":
    arguments = sys.argv[1:]
    if arguments == ["--help"]:
        print(__doc__)
    elif len(arguments) == 2 and arguments[0] == "--smoke-apk":
        try:
            smoke = smoke_release_apk(Path(arguments[1]).resolve(), smoke_environment())
            print(f"Release APK smoke test passed: {smoke}. Reports: {REPORTS}", flush=True)
        except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
            sys.exit(f"Release APK smoke test failed: {error}. Reports: {REPORTS}")
    elif arguments:
        sys.exit("No skip or bypass options are supported. Use --help for prerequisites.")
    else:
        try:
            verify()
        except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
            sys.exit(f"Release verification failed: {error}. Reports: {REPORTS}")
