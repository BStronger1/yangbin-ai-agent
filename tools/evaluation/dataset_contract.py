"""Frozen evaluation dataset and human annotation contracts. Does not run an agent."""
from __future__ import annotations
import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re

from export_records import canonical, digest, ExportError, require

REPO = Path(__file__).resolve().parents[2]
DEFAULT_DATASET = REPO / "evaluation/datasets/travel-eval-v1"
FILES = {"cases.json", "rubric.json", "author-notes.json"}
FAMILIES = {"grounding_replay": ("G", "offline_fixed_output"),
            "workflow_fault": ("F", "mocked_runtime_required"),
            "live_task": ("L", "new_model_task_required")}


def read(path):
    try:
        return json.loads(Path(path).read_text("utf-8-sig"),
                          parse_constant=lambda _: (_ for _ in ()).throw(ValueError()))
    except (ValueError, UnicodeError):
        raise ExportError("Invalid dataset/review JSON") from None


def file_hash(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def merge(base, changes):
    if not isinstance(base, dict) or not isinstance(changes, dict):
        return changes
    result = dict(base)
    for key, value in changes.items():
        result[key] = merge(result.get(key), value)
    return result


def pointer(subject, path):
    require(isinstance(path, str) and path.startswith("/") and len(path) > 1,
            "Evidence requires a non-root JSON Pointer")
    current = subject
    for raw in path[1:].split("/"):
        require(not re.search(r"~(?![01])", raw), "Invalid JSON Pointer escape")
        part = raw.replace("~1", "/").replace("~0", "~")
        if isinstance(current, list):
            require(bool(re.fullmatch(r"0|[1-9][0-9]*", part)), "Invalid array pointer")
            require(int(part) < len(current), "Evidence pointer does not resolve")
            current = current[int(part)]
        else:
            require(isinstance(current, dict) and part in current, "Evidence pointer does not resolve")
            current = current[part]
    return current


def validate_dataset(directory=DEFAULT_DATASET, expected_hash=None):
    directory = Path(directory).resolve()
    manifest = read(directory / "manifest.json")
    require(manifest.get("schemaVersion") == "travel-eval-manifest-v1"
            and manifest.get("status") == "FROZEN_BEFORE_RUN", "Invalid dataset manifest")
    require(set(manifest.get("files", {})) == FILES, "Unexpected dataset file list")
    for name, expected in manifest["files"].items():
        path = directory / name
        require(not path.is_symlink() and path.resolve().parent == directory, "Linked dataset file")
        require(file_hash(path) == expected, "Frozen file fingerprint mismatch: " + name)
    require(manifest.get("datasetHash") == digest(manifest["files"]), "Invalid dataset fingerprint")
    require(expected_hash is None or manifest["datasetHash"] == expected_hash, "Pinned dataset hash mismatch")
    data, rubric, notes = (read(directory / name) for name in ("cases.json", "rubric.json", "author-notes.json"))
    require(data.get("schemaVersion") == "travel-eval-cases-v1"
            and data.get("datasetId") == manifest.get("datasetId")
            and data.get("datasetVersion") == manifest.get("datasetVersion"), "Dataset identity mismatch")
    require(rubric.get("schemaVersion") == "travel-review-rubric-v1"
            and notes.get("status") == "NOT_HUMAN_GOLD", "Invalid rubric or author notes")
    cases = data.get("cases")
    require(isinstance(cases, list) and len(cases) == manifest.get("caseCount"), "Case count mismatch")
    ids, hashes = set(), set()
    for case in cases:
        family = case.get("family")
        require(family in FAMILIES, "Unknown case family")
        prefix, mode = FAMILIES[family]
        case_id = case.get("caseId")
        require(isinstance(case_id, str) and re.fullmatch(prefix + r"[0-9]{2}", case_id)
                and case_id not in ids, "Duplicate or invalid case ID")
        ids.add(case_id)
        require(case.get("executionMode") == mode and case.get("runStatus") == "NOT_RUN",
                "Frozen scenarios must not contain execution results")
        require(case.get("origin") in {"agent_authored_synthetic", "agent_authored_synthetic_prompt"},
                "Incorrect case provenance")
        value = case.get("input")
        require(isinstance(value, dict) and case.get("inputHash") == digest(value), "Input hash mismatch")
        require(case["inputHash"] not in hashes, "Duplicate input")
        hashes.add(case["inputHash"])
        require(case.get("caseHash") == digest({k: v for k, v in case.items() if k != "caseHash"}),
                "Case hash mismatch")
        dimensions = case.get("reviewDimensions")
        require(isinstance(dimensions, list) and dimensions
                and len(set(dimensions)) == len(dimensions)
                and all(d in rubric["dimensions"] for d in dimensions), "Invalid review dimensions")
        require(isinstance(value.get("requirements"), dict), "Missing structured requirements")
        if family == "grounding_replay":
            require(isinstance(value.get("itinerary"), dict) and isinstance(value.get("evidence"), dict),
                    "Fixed replay needs output and evidence")
        elif family == "workflow_fault":
            require(isinstance(value.get("fault"), dict) and value.get("actions")
                    and case.get("assertions"), "Fault scenario needs hooks, actions and assertions")
        else:
            require(value.get("requirements", {}).get("date") is None
                    and value.get("dateBinding") == {"kind": "relative_to_run", "daysAfterRun": 2, "timezone": "Asia/Shanghai"}
                    and case.get("humanLabels") is None
                    and case.get("modelBudget") == {"maxLogicalModelAttempts": 2, "maxRepairAttempts": 0},
                    "Live task requires explicit date binding and budget")
    require(dict(Counter(c["family"] for c in cases)) == manifest.get("familyCounts"), "Family counts mismatch")
    note_list = notes.get("notes", [])
    require(len(note_list) == len(ids) and {n.get("caseId") for n in note_list} == ids,
            "Author notes coverage mismatch")
    independence = manifest.get("independence", {})
    require(independence.get("blind") is False and independence.get("humanGoldComplete") is False
            and independence.get("externalHumanAuthored") is False, "Unsubstantiated independence claim")
    require(manifest.get("execution", {}).get("evaluated") is False
            and manifest["execution"].get("newModelCalls") == 0, "Manifest must not claim execution")
    drift = []
    for relative, expected in manifest["baseline"]["files"].items():
        path = (REPO / relative).resolve()
        require(path.is_relative_to(REPO), "Baseline path escapes repository")
        if not path.is_file() or file_hash(path) != expected:
            drift.append(relative)
    development_path = "src/test/resources/quality/plan-quality-regression-v2.json"
    require(development_path not in drift, "Development set changed; original overlap audit cannot be reproduced")
    development = read(REPO / development_path)
    old_inputs = [merge(development["defaults"], c["overrides"]) for c in development["cases"]]
    old_hashes = {digest(value) for value in old_inputs}
    old_texts = {digest(value.get("itinerary")) for value in old_inputs}
    overlap = [c["caseId"] for c in cases if c["family"] == "grounding_replay"
               and (c["inputHash"] in old_hashes or digest(c["input"]["itinerary"]) in old_texts)]
    require(not overlap, "Exact duplicate of development input or itinerary")
    return manifest, cases, rubric, {
        "datasetHash": manifest["datasetHash"], "caseCount": len(cases),
        "familyCounts": manifest["familyCounts"], "developmentCaseCount": len(old_inputs),
        "exactInputOrItineraryOverlap": 0, "baselineSourceDrift": drift,
        "humanReviewedCases": 0, "evaluatedCases": 0,
        "interpretation": "Integrity/coverage audit only; not a model evaluation or proof of semantic independence."}


def review_packet(directory, reviewer_id):
    manifest, cases, rubric, _ = validate_dataset(directory)
    require(isinstance(reviewer_id, str) and re.fullmatch(r"[A-Za-z0-9_-]{1,64}", reviewer_id),
            "Invalid reviewer alias")
    rows = []
    for case in cases:
        if case["family"] != "grounding_replay":
            continue
        rows.append({"caseId": case["caseId"], "caseHash": case["caseHash"],
                     "subjectHash": digest(case["input"]), "reviewSubject": case["input"],
                     "annotations": {dim: {"label": None, "reason": "", "evidencePointers": []}
                                     for dim in case["reviewDimensions"]}})
    return {"schemaVersion": "travel-human-review-v1", "datasetHash": manifest["datasetHash"],
            "rubricHash": manifest["files"]["rubric.json"], "rubric": rubric,
            "reviewerId": reviewer_id, "reviewerKind": "human",
            "humanDeclaration": False, "independentDeclaration": False, "reviewedAt": None,
            "scope": "fixed_output_only", "awaitingRunCases": 12, "cases": rows}


def validate_review(directory, review, require_complete=False):
    manifest, cases, rubric, _ = validate_dataset(directory)
    require(review.get("schemaVersion") == "travel-human-review-v1"
            and review.get("datasetHash") == manifest["datasetHash"]
            and review.get("rubricHash") == manifest["files"]["rubric.json"]
            and review.get("rubric") == rubric, "Review dataset/rubric binding mismatch")
    require(review.get("reviewerKind") == "human"
            and isinstance(review.get("reviewerId"), str)
            and re.fullmatch(r"[A-Za-z0-9_-]{1,64}", review["reviewerId"]), "Human reviewer alias required")
    require(review.get("scope") == "fixed_output_only" and review.get("awaitingRunCases") == 12,
            "Invalid review scope")
    eligible = {c["caseId"]: c for c in cases if c["family"] == "grounding_replay"}
    rows = review.get("cases")
    require(isinstance(rows, list) and len(rows) == len(eligible), "Review coverage mismatch")
    seen, pending, filled = set(), 0, 0
    for row in rows:
        case_id = row.get("caseId")
        require(case_id in eligible and case_id not in seen, "Unknown or duplicate review case")
        seen.add(case_id)
        case = eligible[case_id]
        require(row.get("caseHash") == case["caseHash"]
                and row.get("subjectHash") == digest(case["input"])
                and row.get("reviewSubject") == case["input"], "Review subject changed")
        annotations = row.get("annotations")
        require(isinstance(annotations, dict) and set(annotations) == set(case["reviewDimensions"]),
                "Annotation dimensions mismatch")
        for dim, annotation in annotations.items():
            require(isinstance(annotation, dict), "Invalid annotation")
            label = annotation.get("label")
            if label is None:
                pending += 1
                continue
            require(label in rubric["dimensions"][dim]["labels"], "Invalid annotation label")
            require(isinstance(annotation.get("reason"), str) and annotation["reason"].strip()
                    and isinstance(annotation.get("evidencePointers"), list) and annotation["evidencePointers"],
                    "Annotation needs rationale and source pointers")
            for path in annotation["evidencePointers"]:
                pointer(case["input"], path)
            filled += 1
    if filled or require_complete:
        require(review.get("humanDeclaration") is True and review.get("independentDeclaration") is True,
                "Human/independent review declarations are missing")
        try:
            dt = datetime.fromisoformat(review.get("reviewedAt", "").replace("Z", "+00:00"))
            require(dt.tzinfo is not None, "Review timestamp needs timezone")
        except (ValueError, TypeError, AttributeError):
            raise ExportError("Review timestamp required") from None
    require(not require_complete or pending == 0, "Review is incomplete")
    return {"reviewerId": review["reviewerId"], "caseCount": len(rows),
            "filledDimensions": filled, "pendingDimensions": pending,
            "complete": pending == 0 and bool(rows),
            "identityVerification": "self_declared_not_authenticated"}


def compare_reviews(directory, first, second):
    validate_review(directory, first, require_complete=True)
    validate_review(directory, second, require_complete=True)
    require(first["reviewerId"] != second["reviewerId"], "Two distinct reviewer aliases required")
    other = {row["caseId"]: row for row in second["cases"]}
    dimensions, conflicts = [], 0
    for row in first["cases"]:
        for dim, annotation in row["annotations"].items():
            left, right = annotation["label"], other[row["caseId"]]["annotations"][dim]["label"]
            same = left == right
            conflicts += int(not same)
            dimensions.append({"caseId": row["caseId"], "dimension": dim,
                               "firstLabel": left, "secondLabel": right,
                               "status": "AGREED" if same else "CONFLICT",
                               "consensusLabel": left if same else None})
    return {"schemaVersion": "travel-review-comparison-v1", "datasetHash": first["datasetHash"],
            "reviewHashes": [digest(first), digest(second)], "reviewers": [first["reviewerId"], second["reviewerId"]],
            "comparedDimensions": len(dimensions), "agreedDimensions": len(dimensions) - conflicts,
            "conflictDimensions": conflicts, "dimensions": dimensions,
            "semanticAccuracy": None,
            "interpretation": "Agreement is not accuracy; conflicts require documented adjudication."}


def write_new(path, value, dataset):
    path = Path(path).resolve()
    require(not path.is_relative_to(Path(dataset).resolve()), "Review output must be outside frozen dataset")
    path.parent.mkdir(parents=True, exist_ok=True)
    # Exclusive creation; never overwrite a prior reviewer response.
    with path.open("x", encoding="utf-8") as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.write("\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, default=DEFAULT_DATASET)
    sub = parser.add_subparsers(dest="command", required=True)
    audit = sub.add_parser("validate")
    audit.add_argument("--expected-hash")
    prepare = sub.add_parser("prepare-review")
    prepare.add_argument("--reviewer", required=True)
    prepare.add_argument("--output", type=Path, required=True)
    check = sub.add_parser("check-review")
    check.add_argument("review", type=Path)
    check.add_argument("--require-complete", action="store_true")
    compare = sub.add_parser("compare-reviews")
    compare.add_argument("first", type=Path)
    compare.add_argument("second", type=Path)
    compare.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    try:
        if args.command == "validate":
            result = validate_dataset(args.dataset, args.expected_hash)[3]
        elif args.command == "prepare-review":
            result = review_packet(args.dataset, args.reviewer)
            write_new(args.output, result, args.dataset)
            result = {"created": str(args.output), "pendingCases": len(result["cases"]), "humanLabels": 0}
        elif args.command == "check-review":
            result = validate_review(args.dataset, read(args.review), args.require_complete)
        else:
            result = compare_reviews(args.dataset, read(args.first), read(args.second))
            write_new(args.output, result, args.dataset)
    except (ExportError, OSError, KeyError, TypeError) as error:
        parser.exit(2, (str(error) if isinstance(error, ExportError) else "Dataset/review file or format error") + "\n")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
