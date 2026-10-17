"""Read-only, owner-scoped export of persisted task/plan/repair snapshots. No network."""
from __future__ import annotations

import argparse
import csv
import hashlib
import io
import json
from pathlib import Path
import re
from collections import Counter
from datetime import datetime, timezone
import uuid

SCHEMA = "agent-evaluation-records-v1"
UUID = r"[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"
STATUSES = {"QUEUED", "RUNNING", "CANCELLING", "AWAITING_CONFIRMATION",
            "SUCCEEDED", "FAILED", "CANCELLED", "INTERRUPTED"}
TERMINAL = {"SUCCEEDED", "FAILED", "CANCELLED"}
REPAIR_STATUSES = {"RUNNING", "PUBLISHING", "REPAIRED", "REJECTED", "FAILED",
                   "INTERRUPTED", "CONFLICT"}
SAMPLE_SOURCES = {"historical_runtime", "synthetic_fixture"}


class ExportError(ValueError):
    """Safe diagnostics: never include source text or credentials."""


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True,
                      separators=(",", ":"), allow_nan=False).encode("utf-8")


def digest(value):
    return hashlib.sha256(canonical(value)).hexdigest()


def require(condition, message):
    if not condition:
        raise ExportError(message)


def count(value):
    return isinstance(value, int) and not isinstance(value, bool) and value >= 0


def timestamp(value):
    require(isinstance(value, str), "Missing or invalid timestamp")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        require(parsed.tzinfo is not None, "Timestamp requires timezone")
        return parsed
    except (ValueError, OverflowError):
        raise ExportError("Invalid timestamp") from None


def quality(report):
    if report is None:
        return None
    require(isinstance(report, dict), "Invalid source-check report")
    findings = report.get("findings")
    require(isinstance(findings, list), "Invalid source-check findings")
    codes = []
    for finding in findings:
        require(isinstance(finding, dict) and isinstance(finding.get("code"), str),
                "Invalid source-check finding")
        if finding.get("severity") == "warning":
            codes.append(finding["code"])
    for key in ("matchedStops", "totalStops"):
        require(count(report.get(key)), "Invalid source-check counts")
    require(report["matchedStops"] <= report["totalStops"], "Invalid matched-stop count")
    return {key: report.get(key) for key in ("ruleVersion", "checkedAt", "status",
                                            "matchedStops", "totalStops")} | {
        "warningCount": len(codes), "warningCodes": sorted(set(codes)),
        "semanticCorrectness": None,
    }


def provenance(value):
    if value is None:
        return None
    require(isinstance(value, dict) and value.get("schemaVersion") == "execution-attempt-v1",
            "Invalid execution provenance")
    require(isinstance(value.get("stepId"), str) and count(value.get("attempt"))
            and value["attempt"] > 0 and isinstance(value.get("inputHash"), str)
            and re.fullmatch(r"[a-f0-9]{64}", value["inputHash"])
            and value.get("inputHashFormat") == "jackson-sorted-json-v1",
            "Invalid execution attempt fingerprint")
    timestamp(value.get("reservedAt"))
    revision = value.get("codeRevision")
    require(revision is None or (isinstance(revision, str)
            and re.fullmatch(r"[a-fA-F0-9]{7,40}(?:-dirty)?", revision)), "Invalid release revision")
    require(value.get("modelProvider") in {None, "openai", "dashscope"}, "Invalid model provider")
    model = value.get("configuredModel")
    require(model is None or (isinstance(model, str)
            and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}", model)), "Invalid configured model")
    return {key: value.get(key) for key in ("schemaVersion", "stepId", "attempt", "reservedAt",
                                           "codeRevision", "modelProvider", "configuredModel",
                                           "inputHash", "inputHashFormat")}


class Snapshot:
    def __init__(self, root):
        self.root = Path(root).resolve(strict=True)
        self.seen = {}
        self.directories = {}

    def path(self, relative):
        path = self.root / relative
        require(path.resolve().is_relative_to(self.root), "Source path escapes data root")
        # Reject links even if they currently point within the snapshot.
        for part in [path, *path.parents]:
            if part == self.root:
                break
            require(not part.is_symlink() and not getattr(part, "is_junction", lambda: False)(),
                    "Linked source paths are not supported")
        return path

    def listing(self, relative, pattern):
        directory = self.path(relative)
        if not directory.exists():
            names = []
        else:
            require(directory.is_dir(), "Expected source directory")
            names = sorted(p.name for p in directory.iterdir() if re.fullmatch(pattern, p.name))
        self.directories[(relative, pattern)] = names
        return names

    def read(self, relative, required=True, binary=False):
        path = self.path(relative)
        if not path.exists():
            self.seen[relative] = None
            require(not required, "Missing linked source: " + relative)
            return None
        require(path.is_file() and path.stat().st_size <= 20_000_000,
                "Invalid or oversized source: " + relative)
        raw = path.read_bytes()
        self.seen[relative] = hashlib.sha256(raw).hexdigest()
        if binary:
            return self.seen[relative]
        try:
            value = json.loads(raw.decode("utf-8-sig"),
                               parse_constant=lambda _: (_ for _ in ()).throw(ValueError()))
            require(isinstance(value, dict), "Expected JSON object: " + relative)
            return value
        except (UnicodeError, ValueError):
            raise ExportError("Invalid JSON source: " + relative) from None

    def reference(self, relative):
        return {"path": relative, "sha256": self.seen[relative]}

    def verify_unchanged(self):
        for relative, original in self.seen.items():
            path = self.path(relative)
            current = hashlib.sha256(path.read_bytes()).hexdigest() if path.exists() else None
            require(current == original, "Source changed during export; use a stable backup")
        for (relative, pattern), names in list(self.directories.items()):
            require(self.listing(relative, pattern) == names,
                    "Source listing changed during export; use a stable backup")


def validate_plan(plan, plan_id, owner, version=None):
    require(isinstance(plan, dict) and plan.get("id") == plan_id
            and plan.get("owner", "admin") == owner
            and count(plan.get("version")) and plan["version"] > 0
            and (version is None or plan["version"] == version), "Invalid linked plan identity")


def normalize_task(snapshot, task, relative, owner):
    task_id = task.get("id")
    require(isinstance(task_id, str) and re.fullmatch(UUID, task_id)
            and relative == "tasks/" + task_id + ".json", "Invalid task identity")
    require(task.get("status") in STATUSES, "Unknown task status")
    created, updated = timestamp(task.get("createdAt")), timestamp(task.get("updatedAt"))
    require(updated >= created, "Task timestamps are out of order")
    steps, events = task.get("steps"), task.get("events")
    require(isinstance(steps, list) and isinstance(events, list), "Missing task history")
    normalized_steps = []
    for step in steps:
        require(isinstance(step, dict) and isinstance(step.get("id"), str)
                and count(step.get("attempts")) and count(step.get("durationMs")),
                "Invalid task step")
        normalized_steps.append({key: step.get(key) for key in
                                 ("id", "status", "attempts", "startedAt", "finishedAt", "durationMs")})
    require(len({s["id"] for s in normalized_steps}) == len(steps), "Duplicate step IDs")
    attempts = task.get("executionAttempts", [])
    require(isinstance(attempts, list), "Invalid execution attempt list")
    attempts = [provenance(value) for value in attempts]
    step_counts = {step["id"]: step["attempts"] for step in normalized_steps}
    require(all(value is not None and value["stepId"] in step_counts
                and value["attempt"] <= step_counts[value["stepId"]] for value in attempts),
            "Execution provenance does not match steps")
    require(len({(a["stepId"], a["attempt"]) for a in attempts}) == len(attempts),
            "Duplicate execution provenance")
    normalized_events = []
    for event in events:
        require(isinstance(event, dict) and count(event.get("sequence"))
                and isinstance(event.get("type"), str), "Invalid task event")
        normalized_events.append({key: event.get(key) for key in ("sequence", "time", "stepId", "type")})
    sequences = [e["sequence"] for e in normalized_events]
    require(sequences == sorted(set(sequences)), "Invalid event sequence")
    for key in ("modelCalls", "toolCalls", "failedAttempts"):
        require(count(task.get(key)), "Missing task counter: " + key)

    embedded = task.get("plan")
    plans, links, repair = [], [snapshot.reference(relative)], None
    if embedded is not None:
        # TaskWorkflow saves plans using the stable task UUID.
        validate_plan(embedded, task_id, owner)
    names = snapshot.listing("plans", task_id + r"-[1-9][0-9]*\.json")
    plan_objects = {}
    for name in sorted(names, key=lambda n: int(n.rsplit("-", 1)[1][:-5])):
        version = int(name.rsplit("-", 1)[1][:-5])
        path = "plans/" + name
        plan = snapshot.read(path)
        validate_plan(plan, task_id, owner, version)
        plan_objects[version] = plan
        pdf_path = f"pdf/{owner}/plan-{task_id}-v{version}.pdf"
        pdf_hash = snapshot.read(pdf_path, required=False, binary=True)
        links.append(snapshot.reference(path))
        if pdf_hash is not None:
            links.append(snapshot.reference(pdf_path))
        plans.append({"planId": task_id, "version": version, "createdAt": plan.get("createdAt"),
                      "contentHash": digest(plan), "itineraryHash": digest(plan.get("itinerary")),
                      "evidenceHash": digest(plan.get("evidence")), "sourcesHash": digest(plan.get("sources")),
                      "quality": quality(plan.get("quality")),
                      "pdf": {"status": "present" if pdf_hash else "missing",
                              "sha256": pdf_hash, "path": pdf_path}})
    if embedded is not None:
        saved = plan_objects.get(embedded["version"])
        require(saved is not None and digest(saved) == digest(embedded),
                "Task embedded plan differs from saved version")
    repair_path = "repairs/" + task_id + ".json"
    ledger = snapshot.read(repair_path, required=False)
    if ledger is not None:
        require(ledger.get("owner") == owner and ledger.get("planId") == task_id
                and ledger.get("status") in REPAIR_STATUSES
                and ledger.get("attempts") == 1 and type(ledger.get("attempts")) is int,
                "Invalid linked repair identity or reservation")
        original = ledger.get("original")
        validate_plan(original, task_id, owner)
        require(digest(plan_objects.get(original["version"])) == digest(original),
                "Repair original differs from saved version")
        before, after = quality(ledger.get("beforeQuality")), quality(ledger.get("afterQuality"))
        require(before is not None, "Repair baseline report is missing")
        comparable = (after is not None and before["ruleVersion"] is not None
                      and before["ruleVersion"] == after["ruleVersion"])
        target = ledger.get("targetVersion")
        if ledger["status"] in {"PUBLISHING", "REPAIRED"}:
            require(type(target) is int and target == original["version"] + 1
                    and after is not None and isinstance(ledger.get("candidate"), dict),
                    "Incomplete repair publication")
        if ledger["status"] == "REPAIRED":
            published = plan_objects.get(target)
            require(published is not None and digest(published.get("itinerary")) == digest(ledger.get("candidate"))
                    and digest(published.get("quality")) == digest(ledger.get("afterQuality")),
                    "Published repair differs from ledger")
            for field in ("requirements", "sources", "evidence"):
                require(digest(published.get(field)) == digest(original.get(field)),
                        "Published repair changed original inputs")
        repair_attempt = provenance(ledger.get("executionAttempt"))
        require(repair_attempt is None or (repair_attempt["stepId"] == "repair"
                and repair_attempt["attempt"] == 1), "Invalid repair provenance")
        links.append(snapshot.reference(repair_path))
        repair = {"status": ledger["status"], "sourceVersion": original["version"],
                  "targetVersion": target, "reservedAttempts": 1,
                  "actualUpstreamRequests": None, "durationMs": None,
                  "createdAt": ledger.get("createdAt"), "updatedAt": ledger.get("updatedAt"),
                  "before": before, "after": after, "sameRuleVersion": comparable,
                  "warningReduction": before["warningCount"] - after["warningCount"] if comparable else None,
                  "candidateHash": digest(ledger.get("candidate")), "executionAttempt": repair_attempt}
    input_value = {key: task.get(key) for key in ("prompt", "requirements", "skill")}
    require(isinstance(input_value["prompt"], str) and isinstance(input_value["requirements"], dict),
            "Missing task input")
    return {
        "schemaVersion": SCHEMA, "taskId": task_id, "status": task["status"],
        "createdAt": task["createdAt"], "updatedAt": task["updatedAt"],
        "taskCompleted": task["status"] == "SUCCEEDED",
        "inputHash": digest(input_value),
        "draftContextHash": digest({key: task.get(key) for key in
                                   ("prompt", "requirements", "skill", "sources", "evidence")}),
        "executionPlanHash": digest(task.get("executionPlan")),
        "runtime": {"codeRevision": None, "model": None,
                    "reason": "see_per_attempt_records" if attempts else "not_recorded_in_v1_v4_state",
                    "attempts": attempts},
        "metrics": {"persistedStepDurationMs": sum(s["durationMs"] for s in normalized_steps),
                    "createdToUpdatedMs": int((updated - created).total_seconds() * 1000),
                    "modelStepAttempts": task["modelCalls"], "toolStepAttempts": task["toolCalls"],
                    "failedAttempts": task["failedAttempts"], "actualUpstreamRequests": None,
                    "promptTokens": None, "completionTokens": None, "cost": None},
        "steps": normalized_steps, "events": normalized_events,
        "eventHistoryTruncated": bool(sequences and sequences[0] > 1),
        "failure": {"present": task.get("error") is not None,
                    "errorHash": digest(task["error"]) if task.get("error") is not None else None,
                    "eventTypes": sorted({e["type"] for e in normalized_events
                                         if e["type"] in {"STEP_FAILED", "QUEUE_FULL", "STORAGE_ERROR", "INTERRUPTED"}})},
        "taskPlanVersion": embedded["version"] if embedded is not None else None,
        "plans": plans, "repair": repair, "sourceFiles": links,
        "semanticCorrectness": None,
    }


def export_snapshot(data_root, owner, output, sample_source):
    require(owner == "admin" or re.fullmatch(r"[a-f0-9]{32}", owner), "Invalid owner")
    require(sample_source in SAMPLE_SOURCES, "Invalid sample source")
    snapshot = Snapshot(data_root)
    destination = Path(output).resolve()
    require(not destination.is_relative_to(snapshot.root), "Output must be outside input data root")
    require(snapshot.path("tasks").is_dir(), "Tasks directory is required")
    names = snapshot.listing("tasks", UUID + r"\.json")
    records = []
    for name in names:
        relative = "tasks/" + name
        task = snapshot.read(relative)
        task_owner = task.get("owner")
        require(isinstance(task_owner, str) and (task_owner == "admin" or re.fullmatch(r"[a-f0-9]{32}", task_owner)),
                "Invalid persisted task owner")
        if task_owner != owner:
            continue
        records.append(normalize_task(snapshot, task, relative, owner))
    snapshot.verify_unchanged()
    source_files = sorted([ref for record in records for ref in record["sourceFiles"]],
                          key=lambda ref: ref["path"])
    input_hash = digest({"schemaVersion": SCHEMA, "owner": owner,
                         "sampleSource": sample_source, "sourceFiles": source_files})
    statuses = dict(sorted(Counter(r["status"] for r in records).items()))
    terminal = sum(statuses.get(s, 0) for s in TERMINAL)
    succeeded = statuses.get("SUCCEEDED", 0)
    now = datetime.now(timezone.utc).isoformat()
    run_id = str(uuid.uuid4())
    report = {
        "schemaVersion": SCHEMA, "runId": run_id, "exportedAt": now, "owner": owner,
        "sampleSource": sample_source, "inputHash": input_hash,
        "exporterSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        "summary": {"taskCount": len(records), "statusCounts": statuses,
                    "completion": {"numerator": succeeded, "denominator": len(records),
                                   "value": succeeded / len(records) if records else None},
                    "terminalCompletion": {"numerator": succeeded, "denominator": terminal,
                                           "value": succeeded / terminal if terminal else None},
                    "semanticAccuracy": None, "totalCost": None, "totalTokens": None},
        "limitations": [
            "Read-only historical snapshot export; no new task execution or quality re-evaluation.",
            "Runtime context is per attempt when recorded; legacy versions stay unknown; exporter version is separate.",
            "Release revision is deployment-declared; model is configured alias, not verified provider model version.",
            "Step counters count reservations/attempts, not provider HTTP requests or billing.",
            "Step duration is checkpointed cumulative time; may include interruption downtime.",
            "Created-to-updated time includes user wait and restarts; it is not model latency.",
            "Repair reservations do not prove a model request occurred; repair duration is unavailable.",
            "Zero warnings and successful workflow do not establish semantic correctness.",
            "Only task-linked plans/repairs and stable task PDF names are included.",
            "Use a stable offline backup; this check is not a transactional live snapshot.",
            "Output is private metadata, not an anonymized public dataset.",
        ],
        "sourceFiles": source_files,
    }
    destination.mkdir(parents=True, exist_ok=True)
    staging = destination / ("." + run_id + ".partial")
    staging.mkdir()
    (staging / "report.json").write_bytes(json.dumps(report, ensure_ascii=False, indent=2).encode("utf-8"))
    (staging / "records.jsonl").write_bytes(b"".join(canonical(r) + b"\n" for r in records))
    csv_buffer = io.StringIO(newline="")
    writer = csv.writer(csv_buffer)
    writer.writerow(["taskId", "status", "inputHash", "modelStepAttempts", "toolStepAttempts",
                     "persistedStepDurationMs", "taskPlanVersion", "repairStatus",
                     "sameRuleWarningReduction", "promptTokens", "completionTokens", "cost"])
    for record in records:
        metrics, repair = record["metrics"], record["repair"] or {}
        writer.writerow([record["taskId"], record["status"], record["inputHash"],
                         metrics["modelStepAttempts"], metrics["toolStepAttempts"],
                         metrics["persistedStepDurationMs"], record["taskPlanVersion"],
                         repair.get("status"), repair.get("warningReduction"), None, None, None])
    (staging / "cases.csv").write_bytes(csv_buffer.getvalue().encode("utf-8-sig"))
    snapshot.verify_unchanged()
    published = destination / run_id
    staging.rename(published)
    return published, report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data-root", required=True, type=Path, help="Stable backup containing tasks/plans/repairs/pdf")
    parser.add_argument("--owner", required=True, help="Exact account ID; no implicit admin fallback")
    parser.add_argument("--output", required=True, type=Path, help="Private output directory outside data root")
    parser.add_argument("--sample-source", required=True, choices=sorted(SAMPLE_SOURCES))
    args = parser.parse_args()
    try:
        path, report = export_snapshot(args.data_root, args.owner, args.output, args.sample_source)
    except (ExportError, OSError, TypeError, KeyError) as failure:
        parser.exit(2, (str(failure) if isinstance(failure, ExportError) else
                        "Export failed: source format or filesystem error") + "\n")
    print(json.dumps({"runId": report["runId"], "taskCount": report["summary"]["taskCount"],
                      "inputHash": report["inputHash"], "output": str(path)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
