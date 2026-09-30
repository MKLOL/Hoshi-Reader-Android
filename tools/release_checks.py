"""Fail-closed readers for the test reports used by every release path."""
from __future__ import annotations

from pathlib import Path
import re
import xml.etree.ElementTree as ET

PREFIX = "moe.antimony.hoshi."
REQUIRED_JVM_CLASSES = {
    PREFIX + name for name in (
        "features.sync.integration.RealServerKvClientTest",
        "features.sync.integration.RealServerResumeIntegrationTest",
        "features.sync.integration.SyncIntegrationTest",
        "features.sync.integration.SyncReleaseRegressionTest",
        "features.sync.integration.SyncReleasePerformanceTest",
        "features.sync.http.HttpSyncBatchExchangeTest",
        "features.sync.http.HttpSyncDownloadTest",
        "features.sync.http.HttpSyncFullCycleRunnerTest",
        "features.sync.http.HttpSyncManualSyncTest",
        "features.sync.http.HttpSyncTransferProgressTest",
        "features.sync.http.HttpSyncMetadataIndexTest",
        "features.sync.http.HttpSyncStatisticsConcurrencyTest",
        "features.sync.http.HttpSyncStatisticsValidationCacheTest",
        "features.sync.http.HttpSyncSettingsRepositoryTest",
        "features.sync.http.LiveSyncTestConfigurationTest",
        "features.statistics.StreakHistoryTest",
        "features.statistics.SyncedReadingTrendsTest",
        "features.backup.HoshiBackupRepositoryTest",
        "features.bookshelf.BookTranslationAvailabilityTest",
        "features.storage.StorageCleanupRepositoryTest",
        "features.anki.AnkiConnectBackendTest",
        "navigation.ReaderRoutePayloadStabilityTest",
        "storage.AtomicSidecarTest",
    )
}
# These are optional tests of an external deployment, not the mandatory local simulator.
OPTIONAL_LIVE_TESTS = {
    "freshDeviceDownloadsBookBookmarkChatAndAiSettings",
    "putGetListDeleteRoundTripsAgainstLiveServer",
    "missingKeyReturnsNullNotException",
    "multipartPutFileRoundTripsAgainstLiveServer",
    "payloadCodecRoundTripsAgainstLiveServer",
    "wrongTokenReturnsHttpSyncException",
}
ANDROID_CLASSES = tuple(PREFIX + name for name in (
    "features.bookshelf.BookPretranslationBadgeTest",
    "features.sync.v3.StubKvServerTest",
    "features.sync.v3.V3SyncIntegrationTest",
    "features.sync.v3.V3SyncFailureModeTest",
    "features.sync.v3.V3SyncRaceIntegrationTest",
    "features.sync.v3.V3SyncLargeLibraryTest",
    "features.sync.v3.EpubHttpSyncE2ETest",
    "features.sync.http.HttpSyncPayloadArchiveInstrumentedTest",
    "features.sync.http.HttpSyncBookshelfButtonInstrumentedTest",
    "features.sync.http.HttpSyncDownloadInstrumentedTest",
    "features.sync.http.HttpSyncSleepTransferTest",
    "features.sync.http.HttpSyncTransferProgressInstrumentedTest",
    "features.sync.http.HttpSyncSettingsInputTest",
    "features.sync.http.HttpSyncStatisticsCacheDeviceTest",
    "features.reader.ReaderSelectionUnicodeWebViewTest",
))


def check_junit(directory: Path, required: set[str] = REQUIRED_JVM_CLASSES) -> dict[str, int]:
    passed_classes: set[str] = set()
    seen: set[tuple[str, str]] = set()
    passed = skipped = 0
    reports = sorted(directory.glob("TEST-*.xml"))
    if not reports:
        raise ValueError(f"No JVM test reports in {directory}")
    for report in reports:
        suite = ET.parse(report).getroot()
        cases = suite.findall("testcase")
        if suite.tag != "testsuite" or not cases or int(suite.get("tests", "-1")) != len(cases):
            raise ValueError(f"Empty or inconsistent test report: {report}")
        if int(suite.get("failures", "0")) or int(suite.get("errors", "0")):
            raise ValueError(f"Failed test suite: {report}")
        if int(suite.get("skipped", "0")) != sum(case.find("skipped") is not None for case in cases):
            raise ValueError(f"Inconsistent skipped test count: {report}")
        for case in cases:
            identity = (case.get("classname", ""), case.get("name", ""))
            if not all(identity) or identity in seen:
                raise ValueError(f"Missing or duplicate test identity: {identity}")
            seen.add(identity)
            if case.find("failure") is not None or case.find("error") is not None:
                raise ValueError(f"Failed test: {identity}")
            skip = case.find("skipped")
            if skip is not None:
                optional = identity[0] == PREFIX + "features.sync.http.HttpSyncLiveServerSmokeTest"
                reason = skip.get("message", "") + (skip.text or "")
                if not (optional and identity[1] in OPTIONAL_LIVE_TESTS and "HOSHI_KV_BASE_URL not set" in reason):
                    raise ValueError(f"Unexpected skipped test: {identity}: {reason}")
                skipped += 1
            else:
                passed += 1
                passed_classes.add(identity[0])
    missing = required - passed_classes
    if missing:
        raise ValueError(f"Mandatory test classes did not run: {sorted(missing)}")
    return {"passed": passed, "optional_live_skipped": skipped}


def check_instrumentation(output: str, required: tuple[str, ...] = ANDROID_CLASSES) -> int:
    """adb can exit zero for failed or aborted instrumentation; inspect every result."""
    if re.search(r"^INSTRUMENTATION_(FAILED|ABORTED):|^INSTRUMENTATION_RESULT: (shortMsg|longMsg)=", output, re.MULTILINE):
        raise ValueError("Instrumentation explicitly reported failure or process termination")
    fields: dict[str, str] = {}
    started: set[tuple[str, str]] = set()
    passed: set[tuple[str, str]] = set()
    counts: set[int] = set()
    for line in output.splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: "):
            key, sep, value = line.removeprefix("INSTRUMENTATION_STATUS: ").partition("=")
            if sep:
                fields[key] = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE:"):
            code = int(line.split(":", 1)[1])
            identity = (fields.get("class", ""), fields.get("test", ""))
            if code not in (0, 1) or not all(identity):
                raise ValueError(f"Android test failed, skipped, or aborted: {code} {identity}")
            counts.add(int(fields.get("numtests", "-1")))
            if code == 1:
                if identity in started:
                    raise ValueError(f"Duplicate Android test: {identity}")
                started.add(identity)
            else:
                if identity not in started or identity in passed:
                    raise ValueError(f"Unmatched Android result: {identity}")
                passed.add(identity)
            fields = {}
    summary = re.findall(r"^OK \((\d+) tests?\)\s*$", output, re.MULTILINE)
    final_codes = re.findall(r"^INSTRUMENTATION_CODE: (-?\d+)\s*$", output, re.MULTILINE)
    missing = set(required) - {item[0] for item in passed}
    if (not passed or started != passed or counts != {len(passed)} or
            summary != [str(len(passed))] or final_codes != ["-1"] or missing):
        raise ValueError(f"Incomplete Android run: {len(passed)} passed, missing classes {sorted(missing)}")
    return len(passed)
