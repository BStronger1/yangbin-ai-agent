"""Bind human confirmation, claim-span observations and an independent external applicability check."""
import argparse
import csv
import hashlib
import html
import json
from pathlib import Path
from datetime import datetime
from collections import Counter
from dataset_contract import DEFAULT_DATASET, REPO, read, digest, pointer, require, ExportError
from offline_report import DEFAULT_LABELS, build_report

CONFIRMATION = DEFAULT_LABELS.parent / "human-confirmation-v1.json"
EXTERNAL_DATASET = REPO / "evaluation/datasets/ragtruth-test-source24-v1"


def validate_confirmation(labels, confirmation):
    require(confirmation.get("schemaVersion") == "travel-assisted-human-confirmation-v1"
            and confirmation.get("reviewerKind") == "human"
            and confirmation.get("reviewMode") == "human_confirmation_of_ai_assisted_labels"
            and confirmation.get("independent") is False and confirmation.get("blind") is False
            and confirmation.get("decision") == "ACCEPT_ALL_AS_WRITTEN",
            "Confirmation must retain assisted, non-blind provenance")
    require(confirmation.get("assistantAnnotationHash") == digest(labels)
            and confirmation.get("datasetHash") == labels["datasetHash"], "Confirmed annotation changed")
    require(confirmation.get("scope") == "all_12_fixed_replay_cases_all_36_dimensions"
            and confirmation.get("reviewerId") and confirmation.get("userStatement"),
            "Confirmation scope/explicit statement missing")
    try:
        require(datetime.fromisoformat(confirmation["recordedAt"]).tzinfo is not None, "Timestamp needs timezone")
    except (ValueError, KeyError, TypeError):
        raise ExportError("Invalid confirmation timestamp") from None
    expected = [{"caseId": r["caseId"], "caseHash": r["caseHash"], "subjectHash": r["subjectHash"],
                 "annotationsHash": digest(r["annotations"])} for r in labels["cases"]]
    require(confirmation.get("caseBindings") == expected, "Confirmation case/label binding changed")
    return {"humanConfirmedCases": len(expected), "humanConfirmedDimensions": sum(len(r["annotations"]) for r in labels["cases"]),
            "reviewerCount": 1, "reviewMode": confirmation["reviewMode"], "independentBlindReviews": 0,
            "confirmationHash": digest(confirmation), "assistantAnnotationHash": digest(labels)}


def external_audit(directory=EXTERNAL_DATASET):
    manifest = read(directory / "manifest.json")
    require(manifest.get("schemaVersion") == "ragtruth-subset-manifest-v1"
            and manifest.get("upstreamCommit") == "c103204b9ce28d6bbad859304bf30de72b8ed8fe",
            "Unrecognized external dataset")
    require(set(manifest.get("files", {})) == {"LICENSE", "cases.json"}
            and manifest["datasetHash"] == digest(manifest["files"]), "External manifest mismatch")
    for name, expected in manifest["files"].items():
        require(hashlib.sha256((directory / name).read_bytes()).hexdigest() == expected, "External frozen file changed")
    cases = read(directory / "cases.json")["cases"]
    require(len(cases) == 24, "External case count mismatch")
    require(len({c["caseId"] for c in cases}) == len({c["sourceRecord"]["source_id"] for c in cases}) == 24,
            "Duplicate external response or source group")
    require(dict(Counter(c["sourceRecord"]["task_type"] for c in cases)) == {"QA": 8, "Summary": 8, "Data2txt": 8},
            "External task strata changed")
    for case in cases:
        source, response = case["sourceRecord"], case["responseRecord"]
        require(response["source_id"] == source["source_id"] and response["split"] == "test"
                and response["quality"] == "good"
                and case["caseHash"] == digest({"source": source, "response": response}), "External record binding changed")
    return manifest, cases


def validate_external(observed, directory=EXTERNAL_DATASET):
    manifest, cases = external_audit(directory)
    require(observed.get("schemaVersion") == "source-review-external-applicability-v1"
            and observed.get("datasetHash") == manifest["datasetHash"]
            and observed.get("detectorVersion") == "travel-claims-v1"
            and observed.get("upstreamModelRequests") == 0, "External observations do not match dataset/checker")
    rows = observed.get("cases")
    require(isinstance(rows, list) and len(rows) == 24 and len({r["caseId"] for r in rows}) == 24,
            "External observed coverage mismatch")
    index = {c["caseId"]: c for c in cases}
    for row in rows:
        require(row.get("caseId") in index and row.get("caseHash") == index[row["caseId"]]["caseHash"],
                "External observation binding changed")
        require(row.get("applicable") is False and row.get("prediction") == "NOT_ASSESSABLE" and row.get("reason"),
                "This checker has no external-language adapter; abstention cannot become a correct prediction")
    return {"datasetHash": manifest["datasetHash"], "upstreamCommit": manifest["upstreamCommit"],
            "caseCount": 24, "humanAnnotatedResponsesWithSpans": sum(bool(c["responseRecord"]["labels"]) for c in cases),
            "humanAnnotatedResponsesWithoutSpans": sum(not c["responseRecord"]["labels"] for c in cases),
            "applicableCases": 0, "abstainedCases": 24, "predictionCoverage": {"numerator": 0, "denominator": 24},
            "accuracy": None, "precision": None, "recall": None, "observationHash": digest(observed),
            "interpretation": "Applicability audit only: all 24 out of scope; no external effectiveness evidence.",
            "cases": rows}


def validate_claims(row):
    subject = row["frozenInput"]
    report = row.get("claimReview")
    require(isinstance(report, dict) and report.get("version") == "travel-claims-v1"
            and report.get("inputHashFormat") == "jackson-sorted-json-v1"
            and report.get("inputHash") == row["inputHash"], "Claim report input/version binding mismatch")
    claims = report.get("claims")
    require(isinstance(claims, list), "Claim list missing")
    for claim in claims:
        field = pointer(subject, claim.get("field"))
        start, end = claim.get("start"), claim.get("end")
        require(isinstance(field, str) and type(start) is int and type(end) is int and 0 <= start < end,
                "Invalid claim span")
        encoded = field.encode("utf-16-le")
        require(end * 2 <= len(encoded), "Claim span out of range")
        try:
            extracted = encoded[start * 2:end * 2].decode("utf-16-le")
        except UnicodeDecodeError:
            raise ExportError("Claim span splits a surrogate pair") from None
        require(extracted == claim.get("text"), "Claim text does not match exact UTF-16 span")
        require(claim.get("verdict") in {"SUPPORTED", "UNSUPPORTED", "CONTRADICTED", "NOT_ASSESSABLE", "NOT_ASSERTION"}
                and claim.get("reason") and claim.get("category"), "Missing claim explanation/verdict")
        refs = claim.get("evidencePointers")
        require(isinstance(refs, list), "Evidence references missing")
        if claim["verdict"] in {"SUPPORTED", "UNSUPPORTED", "CONTRADICTED"}:
            require(bool(refs), "Decided claim requires evidence references")
        for ref in refs:
            pointer(subject, ref)
    unsupported = sum(c["verdict"] == "UNSUPPORTED" for c in claims)
    contradicted = sum(c["verdict"] == "CONTRADICTED" for c in claims)
    require(report.get("unsupportedClaims") == unsupported and report.get("contradictedClaims") == contradicted,
            "Claim aggregate does not match individual records")
    return unsupported + contradicted > 0


def diagnostic(rows, key):
    tp = sum(r["referenceUnsupported"] and r[key] for r in rows)
    fp = sum(not r["referenceUnsupported"] and r[key] for r in rows)
    fn = sum(r["referenceUnsupported"] and not r[key] for r in rows)
    tn = sum(not r["referenceUnsupported"] and not r[key] for r in rows)
    return {"truePositive": tp, "falsePositive": fp, "falseNegative": fn, "trueNegative": tn,
            "precision": {"numerator": tp, "denominator": tp + fp, "value": tp / (tp + fp) if tp + fp else None},
            "recall": {"numerator": tp, "denominator": tp + fn, "value": tp / (tp + fn) if tp + fn else None},
            "interpretation": "Known synthetic cases, single human confirmation of AI labels, non-blind; not generalization or model accuracy."}


def compare(observations, external, labels=None, confirmation=None):
    labels = read(DEFAULT_LABELS) if labels is None else labels
    confirmation = read(CONFIRMATION) if confirmation is None else confirmation
    base = build_report(observations, labels)
    confirmed = validate_confirmation(labels, confirmation)
    outside = validate_external(external)
    rows = []
    for row in base["cases"]:
        if row["family"] != "grounding_replay":
            continue
        require(row["status"] == "OBSERVED", "All fixed replay outputs required before diagnostic comparison")
        source = row["assistantAnnotations"]["source_support"]["label"]
        require(source in {"SUPPORTED", "UNSUPPORTED", "CONTRADICTED"}, "Case reference is not assessable")
        baseline = any(f["severity"] == "warning" and f["code"] != "BUDGET_EXCEEDED" for f in row["quality"]["findings"])
        rows.append({"caseId": row["caseId"], "caseHash": row["caseHash"], "inputHash": row["inputHash"],
                     "referenceLabel": source, "referenceUnsupported": source != "SUPPORTED",
                     "baselineAlert": baseline, "claimReviewAlert": validate_claims(row),
                     "structure": row["structure"], "reviewSubject": row["frozenInput"],
                     "claimReview": row["claimReview"]})
    return {"schemaVersion": "source-review-comparison-v1", "runId": observations["runId"],
            "datasetHash": base["datasetHash"], "codeSnapshot": observations["codeSnapshot"],
            "confirmation": confirmed, "observationsHash": digest(observations),
            "reporterFileHash": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
            "checkedAt": observations["finishedAt"],
            "comparisonScope": "known_nonblind_assisted_human_confirmed_travel_cases",
            "baselineVersion": "grounding-v2", "claimReviewVersion": "travel-claims-v1",
            "baseline": diagnostic(rows, "baselineAlert"), "candidate": diagnostic(rows, "claimReviewAlert"),
            "external": outside, "upstreamModelRequests": 0,
            "generalSemanticAccuracy": None, "cases": rows}


def render(report):
    esc = lambda s: html.escape(str(s), quote=True)
    cards = []
    for row in report["cases"]:
        quotes = []
        for c in row["claimReview"]["claims"]:
            if c["verdict"] in {"UNSUPPORTED", "CONTRADICTED"}:
                quotes.append("<blockquote>" + esc(c["text"]) + "</blockquote><p>" + esc(c["reason"]) + "</p>")
        cards.append(f'<article><h2>{esc(row["caseId"])} · 人工确认建议 {esc(row["referenceLabel"])}</h2>'
                     f'<p>旧规则提醒：{row["baselineAlert"]} · 逐断言提醒：{row["claimReviewAlert"]} · 结构：{esc(row["structure"]["status"])}</p>'
                     + "".join(quotes) + '<details><summary>原始输入、跨度和证据指针</summary><pre>'
                     + esc(json.dumps(row, ensure_ascii=False, indent=2)) + '</pre></details></article>')
    b, n = report["baseline"], report["candidate"]
    return f'''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>来源逐断言核验对照</title><style>*{{box-sizing:border-box}}body{{font:16px/1.65 system-ui,sans-serif;max-width:1080px;margin:30px auto;padding:0 20px;color:#203329;background:#f4f7f5;overflow-wrap:anywhere}}article,.card{{padding:20px;background:white;border:1px solid #d4dfd7;border-radius:10px;margin:16px 0}}h1{{font-size:28px}}h2{{font-size:19px}}blockquote{{border-left:3px solid #bd8c31;background:#fff7e8;padding:10px;margin:12px 0}}pre{{white-space:pre-wrap;font-size:13px}}table{{border-collapse:collapse;width:100%}}td,th{{border-bottom:1px solid #ddd;padding:10px;text-align:left}}</style>
<h1>来源逐断言核验 · 已知案例对照</h1>
<p class="card">12例 / 36维 AI预填标签已由用户确认。单人、非盲评；这些诊断结果不能代表真实模型准确率或对新任务的泛化能力。</p>
<table><thead><tr><th>检查方法</th><th>检出缺依据案例</th><th>支持案例误报</th></tr></thead><tbody>
<tr><td>原来源规则</td><td>{b["truePositive"]}/{b["truePositive"]+b["falseNegative"]}</td><td>{b["falsePositive"]}/{b["falsePositive"]+b["trueNegative"]}</td></tr>
<tr><td>有限逐断言核验</td><td>{n["truePositive"]}/{n["truePositive"]+n["falseNegative"]}</td><td>{n["falsePositive"]}/{n["falsePositive"]+n["trueNegative"]}</td></tr></tbody></table>
<p class="card">外部人工基准：RAGTruth 24例，当前英文/通用来源均超出核验器适用范围，24例弃权，预测覆盖0/24，外部准确率不可计算。没有把弃权算成通过。</p>
<p>运行 {esc(report["runId"])} · 规则 {esc(report["claimReviewVersion"])} · 上游模型请求0</p>
<p>本次核验未纳入一次纠错门槛；原方案、旧报告和PDF保留。</p>
{"".join(cards)}</html>'''


def write(report, output):
    output = Path(output).resolve()
    require(not output.is_relative_to((REPO / "evaluation/datasets").resolve())
            and not output.is_relative_to(DEFAULT_LABELS.parent.resolve()), "Report cannot overwrite frozen datasets/labels")
    output.mkdir(parents=True, exist_ok=True)
    require(not any((output / n).exists() for n in ("comparison.json", "cases.csv", "index.html")), "Output already exists")
    (output / "comparison.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    (output / "index.html").write_text(render(report), encoding="utf-8")
    with (output / "cases.csv").open("x", encoding="utf-8-sig", newline="") as stream:
        writer = csv.writer(stream); writer.writerow(["caseId", "referenceLabel", "baselineAlert", "claimReviewAlert", "labelOrigin"])
        for row in report["cases"]:
            writer.writerow([row["caseId"], row["referenceLabel"], row["baselineAlert"], row["claimReviewAlert"], "human_confirmed_ai_assisted_nonblind"])


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--observations", type=Path, required=True)
    p.add_argument("--external", type=Path, required=True)
    p.add_argument("--output", type=Path, required=True)
    a = p.parse_args()
    try:
        report = compare(read(a.observations), read(a.external))
        write(report, a.output)
    except (ExportError, OSError, ValueError, KeyError, TypeError) as e:
        p.exit(2, str(e) + "\n")
    print(json.dumps({k: report[k] for k in ("runId", "confirmation", "baseline", "candidate")}, ensure_ascii=False, indent=2))
    print("External prediction coverage: 0/24; abstained: 24. Dashboard:", a.output / "index.html")
if __name__ == "__main__":
    main()
