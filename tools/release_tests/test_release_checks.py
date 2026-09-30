from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

from tools.release_checks import check_instrumentation, check_junit, PREFIX


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


if __name__ == "__main__":
    unittest.main()
