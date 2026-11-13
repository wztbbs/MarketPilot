"""记录与归档的轻量验证；不运行 Maven、Docker、MySQL 或 kill 测试。"""
import importlib.util
import json
from pathlib import Path
import tempfile
import time
import unittest
import zipfile

MODULE = Path(__file__).resolve().parents[1] / "verify_run_contract.py"
spec = importlib.util.spec_from_file_location("verify_run_contract", MODULE)
archive = importlib.util.module_from_spec(spec)
spec.loader.exec_module(archive)


class RunContractArchiveTest(unittest.TestCase):
    def test_source_bundle_contains_selected_sources_and_fingerprints(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            paths = ["pom.xml", "src/main/resources/db/schema.sql", "scripts/verify_run_contract.py",
                     "src/main/java/com/cexpilot/platform/Run.java", "src/test/java/com/cexpilot/platform/run/RunTest.java"]
            for relative in paths:
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(relative)
            output = root / "evidence"
            output.mkdir()
            hashes = archive.capture_sources(root, output)
            self.assertEqual(set(paths), set(hashes))
            with zipfile.ZipFile(output / "source-before-run.zip") as bundle:
                self.assertEqual(set(paths), set(bundle.namelist()))
            self.assertTrue(all(len(value) == 64 for value in hashes.values()))

    def test_reports_are_fresh_and_strip_properties_and_known_credentials(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            reports = root / "target/surefire-reports"
            reports.mkdir(parents=True)
            output = root / "evidence"
            output.mkdir()
            for name in archive.REPORT_NAMES:
                (reports / ("TEST-com.cexpilot.platform.run." + name + ".xml")).write_text(
                    '<testsuite name="suite" tests="3" failures="0" errors="0" skipped="0" time="1">'
                    '<properties><property name="private.key" value="private-value"/></properties>'
                    '<testcase name="example"/><system-out>test-password</system-out></testsuite>')
            summary = archive.archive_reports(root, output, 1, ["test-password"])
            self.assertEqual(set(archive.REPORT_NAMES), set(summary))
            content = (output / "reports/BusinessRunMySqlIT.xml").read_text()
            self.assertNotIn("private-value", content)
            self.assertNotIn("test-password", content)
            self.assertIn("[REDACTED]", content)
            with self.assertRaises(RuntimeError):
                archive.archive_reports(root, output, time.time_ns() + 1000000000)

    def test_incomplete_kill_evidence_cannot_be_reported_as_a_complete_pass(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            cases = {name: "PASS" for name in ("a", "b", "c")}
            (output / "summary.json").write_text(json.dumps({"cases": cases}))
            events = []
            for name in cases:
                events += [dict(scenario=name, event="force_kill_finished", details=dict(faultInjection=True, exitCode=137, pid=123)),
                           dict(scenario=name, event="recovery_process_finished", details=dict(exitCode=0, pid=456)),
                           dict(scenario=name, event="process_started", details=dict(mode="start", pid=123)),
                           dict(scenario=name, event="process_started", details=dict(mode="resume", pid=456))]
                for stage in ("before_kill", "after_kill", "after_recovery"):
                    filename = name + "-" + stage + ".json"
                    (output / filename).write_text("{}")
                    events.append(dict(scenario=name, event="state_snapshot", details=dict(stage=stage, file=filename)))
            reports = {name: dict(tests="3", failures="0", errors="0", skipped="0") for name in archive.REPORT_NAMES}
            (output / "events.jsonl").write_text("\n".join(json.dumps(event) for event in events))
            archive.validate_evidence(output, reports)
            events[0]["details"]["exitCode"] = 0
            (output / "events.jsonl").write_text("\n".join(json.dumps(event) for event in events))
            with self.assertRaises(RuntimeError):
                archive.validate_evidence(output, reports)


if __name__ == "__main__":
    unittest.main()
