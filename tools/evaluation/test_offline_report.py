import copy
import tempfile
import unittest
from pathlib import Path
from dataset_contract import DEFAULT_DATASET, read, validate_dataset, validate_review, ExportError
from offline_report import (DEFAULT_LABELS, validate_assisted, build_report, write_outputs, render_html)


class AssistedAndReportTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.labels = read(DEFAULT_LABELS)
        cls.manifest, cls.cases, _, _ = validate_dataset(DEFAULT_DATASET)

    def observations(self):
        rows = []
        for case in self.cases:
            row = {k: case[k] for k in ("caseId", "caseHash", "inputHash", "family")}
            row.update(frozenInput=case["input"], elapsedMs=0)
            if case["family"] == "grounding_replay":
                row.update(status="OBSERVED", structure={"status": "PASS"},
                           quality={"findings": [], "ruleVersion": "grounding-v2"})
            elif case["family"] == "workflow_fault":
                stub = "G12" if case["caseId"] in {"F05", "F06", "F07"} else "G01"
                itinerary = next(c["input"]["itinerary"] for c in self.cases if c["caseId"] == stub)
                row.update(status="PASS", resolvedInput=dict(case["input"], stubCaseId=stub, stubItinerary=itinerary),
                           assertions=[dict(a, status="PASS", observedEvidence="fixture-test-only") for a in case["assertions"]])
            else:
                row.update(status="NOT_RUN", reason="No real model run")
            rows.append(row)
        return {"schemaVersion": "travel-offline-observations-v1", "runId": "unit-fixture",
                "datasetHash": self.manifest["datasetHash"], "mode": "offline_fixtures",
                "upstreamModelRequests": 0, "externalSourceRequests": 0,
                "finishedAt": "2026-10-16T00:00:00Z", "sourceStableDuringRun": True,
                "codeSnapshot": {"head": "unit-fixture", "fileHashes": {"fixture": "not-real-run"}},
                "cases": copy.deepcopy(rows)}

    def test_assisted_labels_complete_and_not_human(self):
        a = validate_assisted(DEFAULT_DATASET, self.labels)
        self.assertEqual((a["filledDimensions"], a["humanReviewedCases"]), (36, 0))

    def test_human_contract_rejects_assistant_packet(self):
        with self.assertRaises(ExportError):
            validate_review(DEFAULT_DATASET, self.labels, True)

    def test_provenance_cannot_be_relabelled(self):
        for key, value in [("humanDeclaration", True), ("independentDeclaration", True),
                           ("reviewerKind", "human"), ("blind", True)]:
            labels = copy.deepcopy(self.labels); labels[key] = value
            with self.subTest(key=key), self.assertRaises(ExportError):
                validate_assisted(DEFAULT_DATASET, labels)

    def test_changed_subject_rejected(self):
        labels = copy.deepcopy(self.labels)
        labels["cases"][0]["reviewSubject"]["itinerary"]["summary"] = "changed"
        with self.assertRaises(ExportError): validate_assisted(DEFAULT_DATASET, labels)

    def test_duplicate_labels_rejected(self):
        labels = copy.deepcopy(self.labels); labels["cases"][1] = labels["cases"][0]
        with self.assertRaises(ExportError): validate_assisted(DEFAULT_DATASET, labels)

    def test_bad_pointer_rejected(self):
        labels = copy.deepcopy(self.labels)
        labels["cases"][0]["annotations"]["source_support"]["evidencePointers"] = ["/itinerary/nonexistent"]
        with self.assertRaises(ExportError): validate_assisted(DEFAULT_DATASET, labels)

    def test_blank_label_or_rationale_rejected(self):
        for key, value in [("label", None), ("reason", ""), ("evidencePointers", [])]:
            labels = copy.deepcopy(self.labels)
            labels["cases"][0]["annotations"]["source_support"][key] = value
            with self.subTest(key=key), self.assertRaises(ExportError):
                validate_assisted(DEFAULT_DATASET, labels)

    def test_counts_exclude_unrun_and_never_claim_accuracy(self):
        report = build_report(self.observations(), self.labels)
        self.assertEqual(report["counts"]["replayObserved"], 12)
        self.assertEqual(report["counts"]["faultPassed"], 8)
        self.assertEqual(report["counts"]["notRun"], 4)
        self.assertEqual(report["counts"]["humanReviewedCases"], 0)
        for field in ("semanticAccuracy", "falsePositiveRate", "falseNegativeRate", "tokens", "cost"):
            self.assertIsNone(report[field])

    def test_notrun_cannot_be_claimed_passed(self):
        run = self.observations(); run["cases"][-1]["status"] = "PASS"
        with self.assertRaises(ExportError): build_report(run, self.labels)

    def test_changed_case_or_hash_rejected(self):
        for key, value in [("inputHash", "wrong"), ("frozenInput", {}), ("family", "live_task")]:
            run = self.observations(); run["cases"][0][key] = value
            with self.subTest(key=key), self.assertRaises(ExportError): build_report(run, self.labels)

    def test_missing_or_duplicate_run_case_rejected(self):
        run = self.observations(); run["cases"][1] = run["cases"][0]
        with self.assertRaises(ExportError): build_report(run, self.labels)
        run["cases"].pop()
        with self.assertRaises(ExportError): build_report(run, self.labels)

    def test_pass_requires_all_fault_assertions(self):
        run = self.observations(); run["cases"][12]["assertions"].pop()
        with self.assertRaises(ExportError): build_report(run, self.labels)

    def test_failed_harness_is_kept_in_denominator(self):
        run = self.observations(); run["cases"][12]["status"] = "HARNESS_FAILED"
        run["cases"][12]["error"] = "injected test failure"
        report = build_report(run, self.labels)
        self.assertEqual(report["counts"]["faultPassed"], 7)
        self.assertEqual(report["counts"]["faultTotal"], 8)
        self.assertEqual(report["counts"]["harnessFailed"], 1)

    def test_fault_fixture_cannot_change_silently(self):
        run = self.observations(); run["cases"][12]["resolvedInput"]["stubItinerary"] = {}
        with self.assertRaises(ExportError): build_report(run, self.labels)

    def test_missing_source_fingerprint_rejected(self):
        run = self.observations(); run["sourceStableDuringRun"] = False
        with self.assertRaises(ExportError): build_report(run, self.labels)

    def test_budget_warning_not_mislabeled_semantic_conflict(self):
        run = self.observations()
        run["cases"][9]["quality"]["findings"] = [{"code": "BUDGET_EXCEEDED", "severity": "warning"}]
        report = build_report(run, self.labels)
        self.assertEqual(report["cases"][9]["reviewFlags"], [])
        self.assertEqual(report["cases"][9]["warningCount"], 1)

    def test_time_information_not_counted_as_warning(self):
        run = self.observations()
        run["cases"][0]["quality"]["findings"] = [{"code": "SNAPSHOT_TIME_INVALID", "severity": "info"}]
        report = build_report(run, self.labels)
        self.assertEqual(report["cases"][0]["warningCount"], 0)

    def test_report_html_escapes_untrusted_text_and_keeps_24_rows(self):
        report = build_report(self.observations(), self.labels)
        report["cases"][0]["error"] = "<script>alert(1)</script>"
        page = render_html(report)
        self.assertNotIn("<script>alert(1)</script>", page)
        self.assertIn("&lt;script&gt;", page)
        self.assertEqual(page.count("<article "), 24)

    def test_output_does_not_overwrite_previous_report(self):
        report = build_report(self.observations(), self.labels)
        with tempfile.TemporaryDirectory() as tmp:
            write_outputs(report, tmp)
            before = (Path(tmp) / "report.json").read_bytes()
            with self.assertRaises(ExportError): write_outputs(report, tmp)
            self.assertEqual((Path(tmp) / "report.json").read_bytes(), before)


if __name__ == "__main__":
    unittest.main()
