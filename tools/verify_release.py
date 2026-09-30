#!/usr/bin/env python3
"""Run the mandatory release gate. Requires the build toolchain and an API 36 emulator image.

Runs all JVM tests against the local HTTP KV simulator, lint/build checks, then the
critical Android regressions on a newly created disposable emulator. Never installs
on an existing device. Logs and the summary are in build/reports/release-gate/.
Set HOSHI_TEST_SYSTEM_IMAGE to choose another installed system image if necessary.
"""
from __future__ import annotations

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
from tools.release_checks import ANDROID_CLASSES, check_instrumentation, check_junit

REPORTS = REPO / "build/reports/release-gate"


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


def run_android(env: dict[str, str]) -> int:
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
             "--device", "pixel_7"], env=device_env, log=REPORTS / "avd-create.log",
            timeout=120, input_text="no\n")
        with (REPORTS / "emulator.log").open("w") as output:
            process = subprocess.Popen(
                [emulator, "-avd", name, "-port", str(port), "-no-window", "-no-audio",
                 "-no-snapshot", "-no-boot-anim", "-gpu", "swiftshader_indirect"],
                env=device_env, stdout=output, stderr=subprocess.STDOUT,
            )
            verified = False
            try:
                deadline = time.monotonic() + 240
                while time.monotonic() < deadline:
                    if process.poll() is not None:
                        raise RuntimeError("Emulator exited during boot; see emulator.log")
                    state = subprocess.run([adb, "-s", serial, "shell", "getprop", "sys.boot_completed"],
                                           capture_output=True, text=True, timeout=15)
                    if state.returncode == 0 and state.stdout.strip() == "1":
                        break
                    time.sleep(1)
                else:
                    raise RuntimeError("Disposable emulator did not boot within 240 seconds")
                identity = subprocess.run([adb, "-s", serial, "emu", "avd", "name"],
                                          capture_output=True, text=True, check=True, timeout=15).stdout
                if identity.splitlines()[0].strip() != name:
                    raise RuntimeError("Refusing to install: device is not this gate's disposable AVD")
                verified = True
                for setting in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
                    run([adb, "-s", serial, "shell", "settings", "put", "global", setting, "0"],
                        env=env, log=REPORTS / f"{setting}.log", timeout=20)
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
            finally:
                if verified:
                    for filename, args in (("logcat.txt", ["logcat", "-d"]), ("emulator-stop.log", ["emu", "kill"])):
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


if __name__ == "__main__":
    if sys.argv[1:] == ["--help"]:
        print(__doc__)
    elif sys.argv[1:]:
        sys.exit("No skip or bypass options are supported. Use --help for prerequisites.")
    else:
        try:
            verify()
        except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
            sys.exit(f"Release verification failed: {error}. Reports: {REPORTS}")
