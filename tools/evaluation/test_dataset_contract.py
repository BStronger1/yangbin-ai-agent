"""Dataset/review infrastructure tests; simulated reviews are not human labels."""
import copy
import json
from pathlib import Path
import shutil
import tempfile
import unittest

from dataset_contract import (DEFAULT_DATASET, ExportError, compare_reviews, digest, file_hash,
                              pointer, read, review_packet, validate_dataset, validate_review, write_new)

PINNED = "a1c7eda476c3bfedd5cea07bc1851ae920d91883dd39a2995c2b8b00ceef4974"


class DatasetContractTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.dataset = self.root / "dataset"
        shutil.copytree(DEFAULT_DATASET, self.dataset)

    def save(self, name, value):
        (self.dataset / name).write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf8")

    def reseal_fixture(self):
        # Only mutate temporary fixtures to test deeper validation. Never reseal the shipped dataset.
        manifest = read(self.dataset / "manifest.json")
        manifest["files"] = {name: file_hash(self.dataset / name) for name in manifest["files"]}
        manifest["datasetHash"] = digest(manifest["files"])
        self.save("manifest.json", manifest)

    def mutate_case(self, edit):
        data = read(self.dataset / "cases.json")
        edit(data["cases"])
        for case in data["cases"]:
            case["inputHash"] = digest(case["input"])
            case["caseHash"] = digest({k: v for k, v in case.items() if k != "caseHash"})
        self.save("cases.json", data)
        self.reseal_fixture()

    def simulated_complete(self, alias="test-reviewer-a"):
        packet = review_packet(self.dataset, alias)
        packet.update(humanDeclaration=True, independentDeclaration=True, reviewedAt="2026-10-16T15:00:00Z")
        for row in packet["cases"]:
            for dim, annotation in row["annotations"].items():
                annotation.update(label=packet["rubric"]["dimensions"][dim]["labels"][0],
                                  reason="Synthetic test of the annotation contract, not a real human judgment.",
                                  evidencePointers=["/requirements"])
        return packet

    def test_frozen_counts_hash_and_no_exact_development_overlap(self):
        _, _, _, result = validate_dataset(DEFAULT_DATASET, PINNED)
        self.assertEqual(result["familyCounts"], {"grounding_replay": 12, "workflow_fault": 8, "live_task": 4})
        self.assertEqual(result["developmentCaseCount"], 26)
        self.assertEqual(result["exactInputOrItineraryOverlap"], 0)
        self.assertIsInstance(result["baselineSourceDrift"], list)
        self.assertEqual(result["humanReviewedCases"], 0)
        self.assertEqual(result["evaluatedCases"], 0)

    def test_frozen_file_changes_are_detected(self):
        path = self.dataset / "cases.json"
        path.write_bytes(path.read_bytes() + b" ")
        with self.assertRaisesRegex(ExportError, "fingerprint mismatch"):
            validate_dataset(self.dataset)

    def test_independent_pin_rejects_recomputed_manifest(self):
        self.mutate_case(lambda cases: cases[0]["input"]["requirements"].update(budget=481))
        with self.assertRaisesRegex(ExportError, "Pinned dataset"):
            validate_dataset(self.dataset, PINNED)

    def test_duplicate_case_ids_rejected(self):
        self.mutate_case(lambda cases: cases[1].update(caseId="G01"))
        with self.assertRaisesRegex(ExportError, "case ID"):
            validate_dataset(self.dataset)

    def test_duplicate_inputs_rejected(self):
        self.mutate_case(lambda cases: cases[1].update(input=copy.deepcopy(cases[0]["input"])))
        with self.assertRaisesRegex(ExportError, "Duplicate input"):
            validate_dataset(self.dataset)

    def test_live_template_requires_unmaterialized_date(self):
        self.mutate_case(lambda cases: cases[-1]["input"]["requirements"].update(date="2026-11-01"))
        with self.assertRaisesRegex(ExportError, "date binding"):
            validate_dataset(self.dataset)

    def test_frozen_cases_cannot_contain_success_claim(self):
        self.mutate_case(lambda cases: cases[0].update(runStatus="SUCCEEDED"))
        with self.assertRaisesRegex(ExportError, "execution results"):
            validate_dataset(self.dataset)

    def test_review_packet_is_pending_and_excludes_author_guidance(self):
        packet = review_packet(self.dataset, "reviewer-a")
        self.assertEqual(len(packet["cases"]), 12)
        self.assertEqual(packet["awaitingRunCases"], 12)
        for row in packet["cases"]:
            self.assertNotIn("title", row)
            self.assertNotIn("proposedLabels", row)
            self.assertTrue(all(a["label"] is None for a in row["annotations"].values()))
        self.assertFalse(packet["humanDeclaration"])
        result = validate_review(self.dataset, packet)
        self.assertFalse(result["complete"])
        self.assertEqual(result["pendingDimensions"], 36)

    def test_pending_review_cannot_be_used_as_completed_gold(self):
        with self.assertRaises(ExportError):
            validate_review(self.dataset, review_packet(self.dataset, "reviewer-a"), require_complete=True)

    def test_agent_authorship_cannot_be_submitted_as_human_kind(self):
        packet = self.simulated_complete()
        packet["reviewerKind"] = "agent"
        with self.assertRaisesRegex(ExportError, "Human reviewer"):
            validate_review(self.dataset, packet, require_complete=True)

    def test_missing_human_declaration_rejected(self):
        packet = self.simulated_complete()
        packet["humanDeclaration"] = False
        with self.assertRaisesRegex(ExportError, "declarations"):
            validate_review(self.dataset, packet, require_complete=True)

    def test_unresolved_source_pointer_rejected(self):
        packet = self.simulated_complete()
        packet["cases"][0]["annotations"]["source_support"]["evidencePointers"] = ["/invented/source"]
        with self.assertRaisesRegex(ExportError, "does not resolve"):
            validate_review(self.dataset, packet)

    def test_invalid_label_or_empty_reason_rejected(self):
        packet = self.simulated_complete()
        annotation = packet["cases"][0]["annotations"]["source_support"]
        annotation["label"] = "ACCURATE_BECAUSE_NO_WARNING"
        with self.assertRaisesRegex(ExportError, "label"):
            validate_review(self.dataset, packet)
        annotation["label"] = "SUPPORTED"
        annotation["reason"] = ""
        with self.assertRaisesRegex(ExportError, "rationale"):
            validate_review(self.dataset, packet)

    def test_review_subject_cannot_be_modified_after_binding(self):
        packet = self.simulated_complete()
        packet["cases"][0]["reviewSubject"]["requirements"]["budget"] = 999
        with self.assertRaisesRegex(ExportError, "subject changed"):
            validate_review(self.dataset, packet)

    def test_rubric_and_dataset_are_bound(self):
        packet = self.simulated_complete()
        packet["datasetHash"] = "0" * 64
        with self.assertRaisesRegex(ExportError, "binding"):
            validate_review(self.dataset, packet)
        packet = self.simulated_complete()
        packet["rubric"]["dimensions"]["source_support"]["labels"].append("FAKE")
        with self.assertRaisesRegex(ExportError, "binding"):
            validate_review(self.dataset, packet)

    def test_same_reviewer_cannot_supply_two_votes(self):
        packet = self.simulated_complete()
        with self.assertRaisesRegex(ExportError, "distinct"):
            compare_reviews(self.dataset, packet, copy.deepcopy(packet))

    def test_conflicting_reviews_remain_unresolved_not_majority_fabricated(self):
        left, right = self.simulated_complete(), self.simulated_complete("test-reviewer-b")
        right["cases"][0]["annotations"]["source_support"]["label"] = "UNSUPPORTED"
        result = compare_reviews(self.dataset, left, right)
        self.assertEqual(result["comparedDimensions"], 36)
        self.assertEqual(result["conflictDimensions"], 1)
        self.assertIsNone(result["dimensions"][0]["consensusLabel"])
        self.assertEqual(result["dimensions"][0]["status"], "CONFLICT")
        self.assertIsNone(result["semanticAccuracy"])

    def test_incomplete_or_duplicate_review_coverage_rejected(self):
        packet = self.simulated_complete()
        packet["cases"].pop()
        with self.assertRaisesRegex(ExportError, "coverage"):
            validate_review(self.dataset, packet)
        packet = self.simulated_complete()
        packet["cases"][1] = copy.deepcopy(packet["cases"][0])
        with self.assertRaisesRegex(ExportError, "duplicate review"):
            validate_review(self.dataset, packet)

    def test_review_outputs_cannot_overwrite_dataset_or_existing_response(self):
        with self.assertRaisesRegex(ExportError, "outside frozen"):
            write_new(self.dataset / "review.json", {}, self.dataset)
        path = self.root / "review.json"
        write_new(path, {"original": True}, self.dataset)
        with self.assertRaises(FileExistsError):
            write_new(path, {}, self.dataset)
        self.assertEqual(read(path), {"original": True})

    def test_validation_and_packet_creation_leave_frozen_bytes_unchanged(self):
        before = {p.name: file_hash(p) for p in self.dataset.iterdir()}
        packet = review_packet(self.dataset, "reviewer-a")
        validate_review(self.dataset, packet)
        self.assertEqual(before, {p.name: file_hash(p) for p in self.dataset.iterdir()})

    def test_json_pointer_array_and_escaping(self):
        self.assertEqual(pointer({"a/b": [{"~x": None}]}, "/a~1b/0/~0x"), None)
        for path in ("", "/", "/a~2b", "/a~1b/-1", "/a~1b/2"):
            with self.assertRaises(ExportError):
                pointer({"a/b": [{"~x": None}]}, path)


if __name__ == "__main__":
    unittest.main()
