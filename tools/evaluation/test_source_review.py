import copy
import json
import tempfile
import unittest
from pathlib import Path
from dataset_contract import read, digest, DEFAULT_DATASET, ExportError
from offline_report import DEFAULT_LABELS
from source_review_report import (CONFIRMATION, EXTERNAL_DATASET, validate_confirmation, external_audit,
                                  validate_external, validate_claims, diagnostic)
from import_ragtruth import select


class SourceReviewContractTest(unittest.TestCase):
    def setUp(self):
        self.labels = read(DEFAULT_LABELS)
        # Contract-only synthetic fixture. No private human statement is shipped to CI.
        self.confirmation = {
            "schemaVersion": "travel-assisted-human-confirmation-v1", "reviewerKind": "human",
            "reviewMode": "human_confirmation_of_ai_assisted_labels", "independent": False, "blind": False,
            "decision": "ACCEPT_ALL_AS_WRITTEN", "assistantAnnotationHash": digest(self.labels),
            "datasetHash": self.labels["datasetHash"], "scope": "all_12_fixed_replay_cases_all_36_dimensions",
            "reviewerId": "synthetic-unit-test-only", "userStatement": "Synthetic fixture; not actual human evidence",
            "recordedAt": "2026-01-01T00:00:00+00:00",
            "caseBindings": [{"caseId": r["caseId"], "caseHash": r["caseHash"], "subjectHash": r["subjectHash"],
                              "annotationsHash": digest(r["annotations"])} for r in self.labels["cases"]]
        }

    def test_user_confirmation_counts_assisted_not_independent(self):
        result = validate_confirmation(self.labels, self.confirmation)
        self.assertEqual((result["humanConfirmedCases"], result["humanConfirmedDimensions"]), (12, 36))
        self.assertEqual(result["independentBlindReviews"], 0)

    def test_confirmation_does_not_survive_label_edits(self):
        self.labels["cases"][0]["annotations"]["source_support"]["reason"] += " changed"
        with self.assertRaises(ExportError): validate_confirmation(self.labels, self.confirmation)

    def test_confirmation_cannot_be_rebranded_as_blind_or_expanded(self):
        for key, value in [("blind", True), ("independent", True), ("scope", "all_cases")]:
            changed = copy.deepcopy(self.confirmation); changed[key] = value
            with self.subTest(key=key), self.assertRaises(ExportError): validate_confirmation(self.labels, changed)

    def test_confirmation_requires_original_case_bindings(self):
        self.confirmation["caseBindings"][0]["annotationsHash"] = "wrong"
        with self.assertRaises(ExportError): validate_confirmation(self.labels, self.confirmation)

    def external(self):
        manifest, cases = external_audit()
        return {"schemaVersion": "source-review-external-applicability-v1", "datasetHash": manifest["datasetHash"],
                "detectorVersion": "travel-claims-v1", "upstreamModelRequests": 0,
                "cases": [{"caseId": c["caseId"], "caseHash": c["caseHash"], "applicable": False,
                           "prediction": "NOT_ASSESSABLE", "reason": "test out of scope"} for c in cases]}

    def test_external_uses_human_test_split_and_unique_sources(self):
        manifest, cases = external_audit()
        self.assertEqual(len(cases), 24)
        self.assertEqual(len({c["sourceRecord"]["source_id"] for c in cases}), 24)
        self.assertTrue(all(c["responseRecord"]["split"] == "test" for c in cases))

    def test_abstention_cannot_be_counted_as_correct(self):
        result = validate_external(self.external())
        self.assertEqual(result["predictionCoverage"], {"numerator": 0, "denominator": 24})
        self.assertIsNone(result["accuracy"])
        changed = self.external(); changed["cases"][0]["prediction"] = "SUPPORTED"
        with self.assertRaises(ExportError): validate_external(changed)

    def test_external_missing_or_duplicate_case_rejected(self):
        changed = self.external(); changed["cases"][1] = changed["cases"][0]
        with self.assertRaises(ExportError): validate_external(changed)

    def test_external_file_fingerprint_rejects_silent_relabel(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            for name in ("manifest.json", "cases.json", "LICENSE"):
                (folder / name).write_bytes((EXTERNAL_DATASET / name).read_bytes())
            data = read(folder / "cases.json"); data["cases"][0]["responseRecord"]["labels"] = []
            (folder / "cases.json").write_text(json.dumps(data), encoding="utf-8")
            with self.assertRaises(ExportError): external_audit(folder)

    def test_sampling_does_not_use_hallucination_labels(self):
        _, cases = external_audit()
        sources = [c["sourceRecord"] for c in cases]
        responses = [c["responseRecord"] for c in cases]
        first = [c["caseId"] for c in select(sources, responses)]
        changed = copy.deepcopy(responses)
        for response in changed:
            response["labels"] = [{"fake": "label should not affect selection"}]
        self.assertEqual(first, [c["caseId"] for c in select(sources, changed)])

    def claim_row(self):
        subject = {"itinerary": {"summary": "😀票价80元"}, "evidence": {"notices": []}}
        return {"frozenInput": subject, "inputHash": digest(subject),
                "claimReview": {"version": "travel-claims-v1", "inputHash": digest(subject),
                                "inputHashFormat": "jackson-sorted-json-v1", "unsupportedClaims": 1, "contradictedClaims": 0,
                                "claims": [{"field": "/itinerary/summary", "start": 2, "end": 7, "text": "票价80元",
                                            "category": "CURRENT_PRICE", "verdict": "UNSUPPORTED",
                                            "reason": "test evidence absent", "evidencePointers": ["/evidence/notices"]}]}}

    def test_claim_span_is_java_utf16_not_python_codepoints(self):
        self.assertTrue(validate_claims(self.claim_row()))

    def test_bad_span_missing_evidence_and_wrong_binding_rejected(self):
        for change in ("span", "reference", "binding", "counts"):
            row = self.claim_row()
            if change == "span": row["claimReview"]["claims"][0]["start"] = 1
            if change == "reference": row["claimReview"]["claims"][0]["evidencePointers"] = ["/missing"]
            if change == "binding": row["claimReview"]["inputHash"] = "wrong"
            if change == "counts": row["claimReview"]["unsupportedClaims"] = 0
            with self.subTest(change=change), self.assertRaises(ExportError): validate_claims(row)

    def test_diagnostic_denominators_include_misses_and_false_alarms(self):
        result = diagnostic([{"referenceUnsupported": a, "pred": b} for a, b in
                             [(True, True), (True, False), (False, True), (False, False)]], "pred")
        self.assertEqual((result["truePositive"], result["falsePositive"], result["falseNegative"], result["trueNegative"]), (1, 1, 1, 1))
        self.assertEqual(result["recall"]["denominator"], 2)


if __name__ == "__main__":
    unittest.main()
