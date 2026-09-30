from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET
import zipfile

from tools.release_checks import (PREFIX, apk_native_abis, app_has_focus, check_device_abi, check_instrumentation,
                                  check_junit, check_launch, check_smoke, launcher_component)


def android_output(classes=("example.Test",), *, end_code=0):
    records = []
    for index, name in enumerate(classes, 1):
        for code in (1, end_code):
            records.extend((
                f"INSTRUMENTATION_STATUS: class={name}",
                "INSTRUMENTATION_STATUS: test=regression",
                f"INSTRUMENTATION_STATUS: numtests={len(classes)}",
                f"INSTRUMENTATION_STATUS: current={index}",
                f"INSTRUMENTATION_STATUS_CODE: {code}",
            ))
    records.extend((f"OK ({len(classes)} tests)", "INSTRUMENTATION_CODE: -1"))
    return "\n".join(records)


class AndroidReportTest(unittest.TestCase):
    def test_complete_run(self):
        self.assertEqual(2, check_instrumentation(android_output(("a.A", "b.B")), ("a.A", "b.B")))

    def test_zero_tests_is_not_success(self):
        with self.assertRaises(ValueError):
            check_instrumentation("OK (0 tests)\nINSTRUMENTATION_CODE: -1", ())

    def test_failure_error_ignored_and_assumption_are_all_blocking(self):
        for code in (-1, -2, -3, -4):
            with self.subTest(code=code), self.assertRaises(ValueError):
                check_instrumentation(android_output(end_code=code), ("example.Test",))

    def test_truncated_crashed_or_inconsistent_result_is_blocking(self):
        valid = android_output()
        for changed in (
            valid.replace("INSTRUMENTATION_CODE: -1", ""),
            valid.replace("INSTRUMENTATION_CODE: -1", "INSTRUMENTATION_CODE: 0"),
            valid.replace("OK (1 tests)", ""),
            valid.replace("OK (1 tests)", "OK (2 tests)"),
            valid.replace("numtests=1", "numtests=2"),
            valid.split("INSTRUMENTATION_STATUS_CODE: 1")[0],
            valid + "\nINSTRUMENTATION_CODE: -1",
            valid + "\nINSTRUMENTATION_FAILED: Instrumentation run failed",
            valid + "\nINSTRUMENTATION_RESULT: shortMsg=Process crashed.",
        ):
            with self.subTest(output=changed), self.assertRaises(ValueError):
                check_instrumentation(changed, ("example.Test",))

    def test_missing_required_class_or_duplicate_test_is_blocking(self):
        with self.assertRaises(ValueError):
            check_instrumentation(android_output(), ("missing.Class",))
        with self.assertRaises(ValueError):
            check_instrumentation(android_output(("example.Test", "example.Test")), ("example.Test",))

    def test_result_without_start_is_blocking(self):
        output = android_output().split("INSTRUMENTATION_STATUS_CODE: 1\n", 1)[1]
        with self.assertRaises(ValueError):
            check_instrumentation(output, ("example.Test",))


class JvmReportTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)

    def report(self, classname="example.Test", name="regression", result=None, message="", **attrs):
        root = ET.Element("testsuite", tests="1", failures="0", errors="0", skipped="0")
        if result == "skipped":
            root.set("skipped", "1")
        root.attrib.update(attrs)
        case = ET.SubElement(root, "testcase", classname=classname, name=name)
        if result:
            ET.SubElement(case, result, message=message)
        ET.ElementTree(root).write(self.directory / f"TEST-{classname}.xml")

    def test_complete_report(self):
        self.report()
        self.assertEqual({"passed": 1, "optional_live_skipped": 0}, check_junit(self.directory, {"example.Test"}))

    def test_missing_or_filtered_reports_are_blocking(self):
        with self.assertRaises(ValueError):
            check_junit(self.directory, set())
        self.report()
        with self.assertRaises(ValueError):
            check_junit(self.directory, {"missing.RequiredTest"})

    def test_failed_cases_cannot_hide_behind_green_suite_counts(self):
        for result in ("failure", "error", "skipped"):
            self.report(result=result)
            with self.subTest(result=result), self.assertRaises(ValueError):
                check_junit(self.directory, {"example.Test"})

    def test_inconsistent_suite_or_empty_report_is_blocking(self):
        for attrs in ({"tests": "0"}, {"tests": "2"}, {"failures": "1"}, {"errors": "1"}, {"skipped": "1"}):
            self.report(**attrs)
            with self.subTest(attrs=attrs), self.assertRaises(ValueError):
                check_junit(self.directory, {"example.Test"})
        (self.directory / "TEST-example.Test.xml").write_text('<testsuite tests="0"/>')
        with self.assertRaises(ValueError):
            check_junit(self.directory, set())

    def test_only_known_external_smoke_tests_may_skip_for_missing_configuration(self):
        live = PREFIX + "features.sync.http.HttpSyncLiveServerSmokeTest"
        self.report()
        self.report(live, "missingKeyReturnsNullNotException", "skipped", "HOSHI_KV_BASE_URL not set")
        self.assertEqual(1, check_junit(self.directory, {"example.Test"})["optional_live_skipped"])
        self.report(live, "missingKeyReturnsNullNotException", "skipped", "server unavailable")
        with self.assertRaises(ValueError):
            check_junit(self.directory, {"example.Test"})
        self.report(live, "newUntestedFeature", "skipped", "HOSHI_KV_BASE_URL not set")
        with self.assertRaises(ValueError):
            check_junit(self.directory, {"example.Test"})

    def test_empty_or_duplicate_identity_is_blocking(self):
        self.report(name="")
        with self.assertRaises(ValueError):
            check_junit(self.directory, set())
        self.report()
        source = self.directory / "TEST-example.Test.xml"
        (self.directory / "TEST-copy.xml").write_bytes(source.read_bytes())
        with self.assertRaises(ValueError):
            check_junit(self.directory, set())


class ReleaseApkSmokeReportTest(unittest.TestCase):
    PACKAGE = "moe.antimony.hoshi.debug"
    LAUNCH_OK = "Starting: Intent { cmp=moe.antimony.hoshi.debug/moe.antimony.hoshi.MainActivity }\n" \
                "Status: ok\nLaunchState: COLD\nActivity: moe.antimony.hoshi.debug/moe.antimony.hoshi.MainActivity\n" \
                "TotalTime: 812\nWaitTime: 815\nComplete\n"

    def test_native_abis_come_only_from_top_level_lib_directories(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "app.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                for name in ("lib/arm64-v8a/libhoshidicts_jni.so", "lib/arm64-v8a/libc++_shared.so",
                             "assets/lib/x86_64/bundled.so", "lib/x86_64/README.txt", "classes.dex"):
                    archive.writestr(name, b"")
            self.assertEqual({"arm64-v8a"}, apk_native_abis(apk))

    def test_an_emulator_that_cannot_execute_the_apk_fails_loudly(self):
        with self.assertRaisesRegex(ValueError, "cannot run"):
            check_device_abi({"arm64-v8a"}, "x86_64,x86\n")
        with self.assertRaises(ValueError):
            check_device_abi({"arm64-v8a"}, "")
        check_device_abi({"arm64-v8a"}, "arm64-v8a\n")
        check_device_abi({"arm64-v8a"}, "x86_64,x86,arm64-v8a,armeabi-v7a,armeabi")  # ARM translation
        check_device_abi(set(), "x86_64")  # No native code runs anywhere.

    def test_launcher_component_must_belong_to_the_exact_package(self):
        output = "priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true\n" \
                 "moe.antimony.hoshi.debug/moe.antimony.hoshi.MainActivity\n"
        self.assertEqual("moe.antimony.hoshi.debug/moe.antimony.hoshi.MainActivity",
                         launcher_component(output, self.PACKAGE))
        for bad in ("No activity found\n", "", output):
            package = "moe.antimony.hoshi" if bad == output else self.PACKAGE
            with self.subTest(output=bad, package=package), self.assertRaises(ValueError):
                launcher_component(bad, package)

    def test_launch_requires_an_explicit_status(self):
        self.assertTrue(check_launch(self.LAUNCH_OK))
        # A slow first frame is not a failure by itself; the caller must then prove focus.
        self.assertFalse(check_launch(self.LAUNCH_OK.replace("Status: ok", "Status: timeout")))
        for bad in (self.LAUNCH_OK.replace("Status: ok\n", ""),
                    "Error: Activity not started, unable to resolve Intent\n",
                    self.LAUNCH_OK + "Error type 3\n"):
            with self.subTest(output=bad), self.assertRaises(ValueError):
                check_launch(bad)

    # Shaped like `adb shell dumpsys window` on API 36 (one display).
    WINDOWS = """WINDOW MANAGER WINDOWS (dumpsys window windows)
  Window #3 Window{8a1c2e u0 moe.antimony.hoshi.debug/moe.antimony.hoshi.MainActivity}:
    mDisplayId=0 rootTaskId=12 mSession=Session{5d2 4321:u0a190} mClient=android.os.BinderProxy@1f
  mGlobalConfiguration={1.0 310mcc260mnc [en_US] ldltr sw411dp w411dp h914dp 420dpi nrml long port}
  mHasPermanentDpad=false
  mTopFocusedDisplayId=0
  mCurrentFocus={focus}
  mFocusedApp=ActivityRecord{c11d u0 moe.antimony.hoshi.debug/moe.antimony.hoshi.MainActivity t12}
"""

    def test_focus_belongs_only_to_the_apps_own_activity(self):
        focused = self.WINDOWS.replace(
            "{focus}", "Window{8a1c2e u0 moe.antimony.hoshi.debug/moe.antimony.hoshi.MainActivity}")
        self.assertTrue(app_has_focus(focused, self.PACKAGE))
        self.assertFalse(app_has_focus(focused, "moe.antimony.hoshi"))  # The release package differs.
        for focus in ("Window{77 u0 Application Not Responding: moe.antimony.hoshi.debug}",
                      "Window{78 u0 Application Error: moe.antimony.hoshi.debug}",
                      "Window{79 u0 com.google.android.apps.nexuslauncher/com.android.launcher3.Launcher}",
                      "null"):
            with self.subTest(focus=focus):
                self.assertFalse(app_has_focus(self.WINDOWS.replace("{focus}", focus), self.PACKAGE))
        self.assertFalse(app_has_focus("", self.PACKAGE))

    def test_a_live_unchanged_process_with_a_clean_log_passes(self):
        other_app_crash = ("E AndroidRuntime: FATAL EXCEPTION: main\n"
                           "E AndroidRuntime: Process: com.android.systemui, PID: 900\n")
        check_smoke(self.PACKAGE, "4321\n", "4321\n", "I ActivityManager: Start proc 4321\n" + other_app_crash)

    def test_crashes_anrs_native_crashes_and_process_death_are_blocking(self):
        for logcat in (
            "E AndroidRuntime: FATAL EXCEPTION: main\nE AndroidRuntime: Process: moe.antimony.hoshi.debug, PID: 4321\n",
            "E AndroidRuntime: FATAL EXCEPTION: sync\nE AndroidRuntime: Process: moe.antimony.hoshi.debug:sync, PID: 77\n",
            "E ActivityManager: ANR in moe.antimony.hoshi.debug (moe.antimony.hoshi.debug/moe.antimony.hoshi.MainActivity)\n",
            "F DEBUG   : pid: 4321, tid: 4321, name: antimony.hoshi  >>> moe.antimony.hoshi.debug <<<\n",
            # Tombstone header from a secondary `:sync` process (debuggerd format).
            "F DEBUG   : pid: 77, tid: 90, name: DefaultDispatch  >>> moe.antimony.hoshi.debug:sync <<<\n",
            "I ActivityManager: Process moe.antimony.hoshi.debug:sync (pid 77) has died: fg SVC\n",
            "E ActivityManager: ANR in moe.antimony.hoshi.debug:sync\n",
            "I ActivityManager: Process moe.antimony.hoshi.debug (pid 4321) has died: fg TOP\n",
            "W ActivityManager:   Force finishing activity moe.antimony.hoshi.debug/moe.antimony.hoshi.MainActivity\n",
        ):
            with self.subTest(logcat=logcat), self.assertRaises(ValueError):
                check_smoke(self.PACKAGE, "4321", "4321", logcat)

    def test_release_package_is_not_confused_with_the_debug_package(self):
        debug_crash = "E AndroidRuntime: Process: moe.antimony.hoshi.debug, PID: 12\n"
        check_smoke("moe.antimony.hoshi", "44", "44", debug_crash)
        with self.assertRaises(ValueError):
            check_smoke("moe.antimony.hoshi", "44", "44", debug_crash.replace(".debug", ""))

    def test_missing_restarted_or_dead_process_is_blocking(self):
        for launched, after in (("", ""), ("4321", ""), ("4321", "5555"), ("4321 999", "4321 999"), ("err", "err")):
            with self.subTest(launched=launched, after=after), self.assertRaises(ValueError):
                check_smoke(self.PACKAGE, launched, after, "")


if __name__ == "__main__":
    unittest.main()
