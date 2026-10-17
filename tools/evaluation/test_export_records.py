"""Synthetic exporter contract tests; these are not task-quality evaluations."""
import copy
import csv
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from export_records import ExportError, Snapshot, digest, export_snapshot

TASK = "11111111-1111-4111-8111-111111111111"
OTHER_TASK = "22222222-2222-4222-8222-222222222222"
OWNER = "a" * 32
OTHER = "b" * 32


def report(rule="grounding-v2", warnings=1):
    return {"ruleVersion": rule, "checkedAt": "2026-10-16T00:00:00Z",
            "status": "REVIEW_REQUIRED" if warnings else "CHECKED_WITH_LIMITATIONS",
            "matchedStops": 1, "totalStops": 1,
            "findings": [{"code": "PRICE_UNVERIFIED", "severity": "warning",
                          "message": "PRIVATE_DIAGNOSTIC"} for _ in range(warnings)]}


def fixture(task_id=TASK, owner=OWNER):
    requirements = {"city": "上海", "budget": 500, "date": "2026-10-16"}
    plan = {"id": task_id, "version": 1, "owner": owner,
            "createdAt": "2026-10-16T00:00:01Z", "requirements": requirements,
            "itinerary": {"title": "PRIVATE_TITLE", "stops": []},
            "sources": [{"content": "PRIVATE_KNOWLEDGE"}],
            "evidence": {"weatherStatus": "available"}, "quality": report()}
    task = {"id": task_id, "owner": owner, "role": "user", "requestId": "PRIVATE_REQUEST_ID",
            "prompt": "=PRIVATE_PROMPT", "requirements": requirements, "skill": None,
            "status": "SUCCEEDED", "createdAt": "2026-10-16T00:00:00Z",
            "updatedAt": "2026-10-16T00:00:05Z", "modelCalls": 2,
            "toolCalls": 3, "failedAttempts": 0, "error": None,
            "steps": [{"id": "model", "status": "SUCCEEDED", "attempts": 2,
                       "startedAt": "2026-10-16T00:00:01Z",
                       "finishedAt": "2026-10-16T00:00:02Z",
                       "durationMs": 1200, "summary": "PRIVATE_SUMMARY"}],
            "events": [{"sequence": 1, "time": "2026-10-16T00:00:00Z",
                        "stepId": None, "type": "CREATED", "message": "PRIVATE_MESSAGE"}],
            "sources": plan["sources"], "evidence": plan["evidence"],
            "plan": plan, "itinerary": plan["itinerary"]}
    return task, plan


class ExportRecordsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.data = self.root / "data"
        self.output = self.root / "exports"
        (self.data / "tasks").mkdir(parents=True)
        self.task, self.plan = fixture()
        self.save_fixture()

    def write(self, name, value):
        path = self.data / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")

    def save_fixture(self):
        self.write(f"tasks/{TASK}.json", self.task)
        self.write(f"plans/{TASK}-1.json", self.plan)

    def run_export(self, owner=OWNER):
        path, summary = export_snapshot(self.data, owner, self.output, "synthetic_fixture")
        records = [json.loads(line) for line in (path / "records.jsonl").read_text("utf-8").splitlines()]
        return path, summary, records

    def add_repair(self, status="REPAIRED", rule="grounding-v2"):
        target = copy.deepcopy(self.plan)
        target["version"] = 2
        target["itinerary"]["title"] = "PRIVATE_REPAIRED_TITLE"
        target["quality"] = report(rule, 0)
        ledger = {"planId": TASK, "owner": OWNER, "status": status, "attempts": 1,
                  "original": self.plan, "candidate": target["itinerary"], "targetVersion": 2,
                  "beforeQuality": report(), "afterQuality": target["quality"]}
        self.write(f"repairs/{TASK}.json", ledger)
        if status == "REPAIRED":
            self.write(f"plans/{TASK}-2.json", target)
        return ledger, target

    def test_repeatable_records_and_input_hash_without_source_writes(self):
        self.add_repair()
        before = {p: hashlib.sha256(p.read_bytes()).hexdigest() for p in self.data.rglob("*") if p.is_file()}
        path, first, records = self.run_export()
        path2, second, records2 = self.run_export()
        self.assertNotEqual(path, path2)
        self.assertEqual(first["inputHash"], second["inputHash"])
        self.assertEqual(records, records2)
        self.assertEqual(before, {p: hashlib.sha256(p.read_bytes()).hexdigest() for p in self.data.rglob("*") if p.is_file()})
        self.assertEqual(records[0]["taskPlanVersion"], 1)
        self.assertEqual([p["version"] for p in records[0]["plans"]], [1, 2])
        self.assertEqual(records[0]["repair"]["warningReduction"], 1)

    def test_unknown_usage_runtime_and_repair_time_remain_null(self):
        self.add_repair()
        record = self.run_export()[2][0]
        self.assertEqual(record["runtime"]["codeRevision"], None)
        self.assertEqual(record["runtime"]["model"], None)
        for field in ("promptTokens", "completionTokens", "actualUpstreamRequests", "cost"):
            self.assertIsNone(record["metrics"][field])
        self.assertIsNone(record["repair"]["durationMs"])
        self.assertIsNone(record["repair"]["actualUpstreamRequests"])
        self.assertEqual(record["metrics"]["persistedStepDurationMs"], 1200)
        self.assertEqual(record["metrics"]["createdToUpdatedMs"], 5000)

    def test_owner_filter_excludes_other_account_metadata_and_content(self):
        task, plan = fixture(OTHER_TASK, OTHER)
        self.write(f"tasks/{OTHER_TASK}.json", task)
        self.write(f"plans/{OTHER_TASK}-1.json", plan)
        path, summary, records = self.run_export()
        self.assertEqual(len(records), 1)
        self.assertEqual(summary["summary"]["taskCount"], 1)
        self.assertNotIn(OTHER_TASK, "".join(p.read_text("utf-8-sig") for p in path.iterdir()))
        self.assertNotIn(OTHER, json.dumps(summary))

    def test_plan_owner_mismatch_is_fatal(self):
        self.plan["owner"] = OTHER
        self.save_fixture()
        with self.assertRaisesRegex(ExportError, "plan identity"):
            self.run_export()

    def test_repair_owner_mismatch_is_fatal(self):
        ledger, _ = self.add_repair()
        ledger["owner"] = OTHER
        self.write(f"repairs/{TASK}.json", ledger)
        with self.assertRaisesRegex(ExportError, "repair identity"):
            self.run_export()

    def test_missing_linked_plan_does_not_look_like_success(self):
        (self.data / f"plans/{TASK}-1.json").unlink()
        with self.assertRaisesRegex(ExportError, "embedded plan"):
            self.run_export()
        self.assertFalse(self.output.exists())

    def test_embedded_snapshot_cannot_silently_use_latest_plan(self):
        changed = copy.deepcopy(self.plan)
        changed["itinerary"]["title"] = "changed"
        self.write(f"plans/{TASK}-1.json", changed)
        with self.assertRaisesRegex(ExportError, "embedded plan"):
            self.run_export()

    def test_repaired_target_must_equal_durable_candidate(self):
        _, target = self.add_repair()
        target["itinerary"]["title"] = "different"
        self.write(f"plans/{TASK}-2.json", target)
        with self.assertRaisesRegex(ExportError, "Published repair"):
            self.run_export()

    def test_repaired_target_cannot_change_evidence(self):
        _, target = self.add_repair()
        target["evidence"] = {"weatherStatus": "changed"}
        self.write(f"plans/{TASK}-2.json", target)
        with self.assertRaisesRegex(ExportError, "changed original inputs"):
            self.run_export()

    def test_mixed_rule_versions_never_produce_improvement_number(self):
        self.add_repair(rule="grounding-v3")
        repair = self.run_export()[2][0]["repair"]
        self.assertFalse(repair["sameRuleVersion"])
        self.assertIsNone(repair["warningReduction"])

    def test_failed_reserved_repair_is_not_a_confirmed_model_request(self):
        ledger, _ = self.add_repair(status="FAILED")
        ledger.update(candidate=None, afterQuality=None, targetVersion=None)
        self.write(f"repairs/{TASK}.json", ledger)
        repair = self.run_export()[2][0]["repair"]
        self.assertEqual(repair["reservedAttempts"], 1)
        self.assertIsNone(repair["actualUpstreamRequests"])
        self.assertIsNone(repair["warningReduction"])
        self.assertIsNone(repair["after"])

    def test_legacy_quality_stays_unchecked(self):
        self.plan.pop("quality")
        self.save_fixture()
        self.assertIsNone(self.run_export()[2][0]["plans"][0]["quality"])

    def test_legacy_plan_owner_default_only_for_admin(self):
        self.plan.pop("owner")
        self.task["owner"] = "admin"
        self.save_fixture()
        self.assertEqual(len(self.run_export("admin")[2]), 1)

    def test_private_text_not_exported_and_csv_is_consistent(self):
        self.add_repair()
        path, _, records = self.run_export()
        output = "".join(p.read_text("utf-8-sig") for p in path.iterdir())
        self.assertNotIn("PRIVATE_", output)
        rows = list(csv.DictReader(io.StringIO((path / "cases.csv").read_text("utf-8-sig"))))
        self.assertEqual(rows[0]["taskId"], records[0]["taskId"])
        self.assertEqual(rows[0]["cost"], "")
        self.assertEqual(rows[0]["sameRuleWarningReduction"], "1")

    def test_failures_and_cancellation_are_in_denominator_waiting_is_separate(self):
        self.task.update(status="FAILED", error="PRIVATE_ERROR", failedAttempts=1)
        self.save_fixture()
        task, _ = fixture(OTHER_TASK)
        task["status"] = "AWAITING_CONFIRMATION"
        task["plan"] = None
        self.write(f"tasks/{OTHER_TASK}.json", task)
        _, summary, records = self.run_export()
        self.assertEqual(summary["summary"]["completion"], {"numerator": 0, "denominator": 2, "value": 0})
        self.assertEqual(summary["summary"]["terminalCompletion"]["denominator"], 1)
        self.assertTrue(records[0]["failure"]["present"])
        self.assertIsNone(summary["summary"]["semanticAccuracy"])

    def test_empty_owner_scope_has_null_rate_not_zero_success(self):
        _, summary, records = self.run_export(OTHER)
        self.assertEqual(records, [])
        self.assertIsNone(summary["summary"]["completion"]["value"])

    def test_output_inside_data_root_is_rejected(self):
        self.output = self.data / "exports"
        with self.assertRaisesRegex(ExportError, "outside input"):
            self.run_export()

    def test_source_mutation_is_detected(self):
        snapshot = Snapshot(self.data)
        snapshot.read(f"tasks/{TASK}.json")
        self.task["modelCalls"] += 1
        self.save_fixture()
        with self.assertRaisesRegex(ExportError, "changed"):
            snapshot.verify_unchanged()

    def test_new_source_file_is_detected(self):
        snapshot = Snapshot(self.data)
        snapshot.listing("tasks", r".*\.json")
        self.write(f"tasks/{OTHER_TASK}.json", fixture(OTHER_TASK)[0])
        with self.assertRaisesRegex(ExportError, "listing changed"):
            snapshot.verify_unchanged()

    def test_new_previously_missing_pdf_is_detected(self):
        snapshot = Snapshot(self.data)
        relative = f"pdf/{OWNER}/plan-{TASK}-v1.pdf"
        self.assertIsNone(snapshot.read(relative, required=False, binary=True))
        path = self.data / relative
        path.parent.mkdir(parents=True)
        path.write_bytes(b"%PDF-fixture")
        with self.assertRaisesRegex(ExportError, "changed"):
            snapshot.verify_unchanged()

    def test_pdf_content_fingerprint_included(self):
        path = self.data / f"pdf/{OWNER}/plan-{TASK}-v1.pdf"
        path.parent.mkdir(parents=True)
        path.write_bytes(b"%PDF-fixture")
        _, _, records = self.run_export()
        pdf = records[0]["plans"][0]["pdf"]
        self.assertEqual(pdf["sha256"], hashlib.sha256(path.read_bytes()).hexdigest())
        self.assertEqual(pdf["status"], "present")

    def test_new_execution_context_is_preserved_per_attempt(self):
        self.task["executionAttempts"] = [
            {"schemaVersion": "execution-attempt-v1", "stepId": "model", "attempt": 1,
             "reservedAt": "2026-10-16T00:00:00Z", "codeRevision": "abcdef1",
             "modelProvider": "openai", "configuredModel": "model-1",
             "inputHash": "a" * 64, "inputHashFormat": "jackson-sorted-json-v1"},
            {"schemaVersion": "execution-attempt-v1", "stepId": "model", "attempt": 2,
             "reservedAt": "2026-10-16T00:00:01Z", "codeRevision": "abcdef2",
             "modelProvider": "openai", "configuredModel": "model-2",
             "inputHash": "b" * 64, "inputHashFormat": "jackson-sorted-json-v1"}]
        self.save_fixture()
        runtime = self.run_export()[2][0]["runtime"]
        self.assertEqual(runtime["reason"], "see_per_attempt_records")
        self.assertEqual([a["codeRevision"] for a in runtime["attempts"]], ["abcdef1", "abcdef2"])
        self.assertIsNone(runtime["codeRevision"])

    def test_provenance_cannot_claim_unrecorded_attempt(self):
        self.task["executionAttempts"] = [
            {"schemaVersion": "execution-attempt-v1", "stepId": "model", "attempt": 3,
             "reservedAt": "2026-10-16T00:00:00Z", "codeRevision": None,
             "modelProvider": None, "configuredModel": None,
             "inputHash": "a" * 64, "inputHashFormat": "jackson-sorted-json-v1"}]
        self.save_fixture()
        with self.assertRaisesRegex(ExportError, "does not match steps"):
            self.run_export()

    def test_repair_execution_context_kept_separate_from_original_task(self):
        ledger, _ = self.add_repair()
        ledger["executionAttempt"] = {
            "schemaVersion": "execution-attempt-v1", "stepId": "repair", "attempt": 1,
            "reservedAt": "2026-10-16T00:00:00Z", "codeRevision": "abcdef3",
            "modelProvider": "dashscope", "configuredModel": "qwen-test",
            "inputHash": "a" * 64, "inputHashFormat": "jackson-sorted-json-v1"}
        self.write(f"repairs/{TASK}.json", ledger)
        record = self.run_export()[2][0]
        self.assertEqual(record["repair"]["executionAttempt"]["codeRevision"], "abcdef3")
        self.assertEqual(record["runtime"]["attempts"], [])

    def test_missing_owner_does_not_silently_drop_corrupt_task(self):
        self.task.pop("owner")
        self.save_fixture()
        with self.assertRaisesRegex(ExportError, "task owner"):
            self.run_export()

    def test_cancelled_task_remains_in_terminal_denominator(self):
        self.task.update(status="CANCELLED", plan=None)
        self.save_fixture()
        summary = self.run_export()[1]["summary"]
        self.assertEqual(summary["statusCounts"], {"CANCELLED": 1})
        self.assertEqual(summary["terminalCompletion"], {"numerator": 0, "denominator": 1, "value": 0})

    def test_source_link_cannot_escape_snapshot(self):
        outside = self.root / "outside.json"
        outside.write_text("{}", encoding="utf-8")
        link = self.data / "linked.json"
        try:
            link.symlink_to(outside)
        except OSError:
            self.skipTest("OS does not permit creating symlinks")
        with self.assertRaisesRegex(ExportError, "escapes"):
            Snapshot(self.data).read("linked.json")

    def test_missing_counter_is_not_invented_as_zero(self):
        self.task.pop("modelCalls")
        self.save_fixture()
        with self.assertRaisesRegex(ExportError, "counter"):
            self.run_export()

    def test_duplicate_event_sequences_rejected(self):
        self.task["events"].append(copy.deepcopy(self.task["events"][0]))
        self.save_fixture()
        with self.assertRaisesRegex(ExportError, "event sequence"):
            self.run_export()

    def test_trimmed_event_history_is_explicit(self):
        self.task["events"][0]["sequence"] = 12
        self.save_fixture()
        self.assertTrue(self.run_export()[2][0]["eventHistoryTruncated"])

    def test_invalid_identity_and_status_rejected(self):
        self.task["id"] = OTHER_TASK
        self.save_fixture()
        with self.assertRaisesRegex(ExportError, "task identity"):
            self.run_export()
        self.task["id"] = TASK
        self.task["status"] = "=FORMULA"
        self.save_fixture()
        with self.assertRaisesRegex(ExportError, "task status"):
            self.run_export()

    def test_input_fingerprint_is_order_independent_and_change_sensitive(self):
        self.assertEqual(digest({"a": 1, "b": 2}), digest({"b": 2, "a": 1}))
        before = self.run_export()[2][0]["inputHash"]
        self.task["prompt"] = "新的目标"
        self.save_fixture()
        self.assertNotEqual(before, self.run_export()[2][0]["inputHash"])

    def test_invalid_json_fails_without_echoing_source(self):
        (self.data / f"tasks/{TASK}.json").write_text('{"PRIVATE_SECRET": NaN}', encoding="utf-8")
        with self.assertRaisesRegex(ExportError, "Invalid JSON") as failure:
            self.run_export()
        self.assertNotIn("PRIVATE_SECRET", str(failure.exception))

    def test_second_consistency_check_prevents_published_partial_run(self):
        original = Snapshot.verify_unchanged
        calls = []

        def verify(snapshot):
            calls.append(1)
            if len(calls) == 2:
                raise ExportError("Source changed during export")
            return original(snapshot)

        with patch.object(Snapshot, "verify_unchanged", verify):
            with self.assertRaises(ExportError):
                self.run_export()
        self.assertTrue(all(p.name.endswith(".partial") for p in self.output.iterdir()))


if __name__ == "__main__":
    unittest.main()
