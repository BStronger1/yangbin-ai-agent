"""Offline evaluation reports and explicitly non-human assisted annotations."""
import argparse
import csv
import html
import json
import hashlib
from pathlib import Path
from datetime import datetime
from collections import Counter
from dataset_contract import (DEFAULT_DATASET, REPO, read, validate_dataset,
                              pointer, digest, require, ExportError)

DEFAULT_LABELS = REPO / "evaluation/annotations/travel-eval-v1/assistant-review-v1.json"


def validate_assisted(directory, review):
    manifest, cases, rubric, _ = validate_dataset(directory)
    require(review.get("schemaVersion") == "travel-assistant-review-v1"
            and review.get("reviewerKind") == "assistant"
            and review.get("status") == "ASSISTANT_DRAFT_REQUIRES_HUMAN_REVIEW"
            and review.get("humanDeclaration") is False
            and review.get("independentDeclaration") is False
            and review.get("blind") is False, "Assistant provenance must not claim human/independent review")
    require(review.get("datasetHash") == manifest["datasetHash"]
            and review.get("rubricHash") == manifest["files"]["rubric.json"]
            and review.get("rubric") == rubric, "Assistant label dataset/rubric binding mismatch")
    require(isinstance(review.get("reviewerId"), str) and review["reviewerId"], "Missing assistant alias")
    try:
        require(datetime.fromisoformat(review["reviewedAt"]).tzinfo is not None, "Timestamp needs timezone")
    except (ValueError, KeyError, TypeError):
        raise ExportError("Invalid assistant timestamp") from None
    eligible = {c["caseId"]: c for c in cases if c["family"] == "grounding_replay"}
    rows = review.get("cases")
    require(isinstance(rows, list) and len(rows) == len(eligible), "Assistant label coverage mismatch")
    seen = set()
    for row in rows:
        cid = row.get("caseId")
        require(cid in eligible and cid not in seen, "Unknown/duplicate assistant case")
        seen.add(cid)
        case = eligible[cid]
        require(row.get("caseHash") == case["caseHash"]
                and row.get("subjectHash") == digest(case["input"])
                and row.get("reviewSubject") == case["input"], "Assistant review subject changed")
        annotations = row.get("annotations")
        require(isinstance(annotations, dict) and set(annotations) == set(case["reviewDimensions"]),
                "Assistant dimensions mismatch")
        for dim, a in annotations.items():
            require(isinstance(a, dict) and a.get("label") in rubric["dimensions"][dim]["labels"],
                    "Invalid or empty assistant label")
            require(isinstance(a.get("reason"), str) and a["reason"].strip()
                    and isinstance(a.get("evidencePointers"), list) and a["evidencePointers"],
                    "Assistant label requires rationale and evidence")
            for p in a["evidencePointers"]:
                pointer(case["input"], p)
    return {"caseCount": len(rows), "filledDimensions": sum(len(r["annotations"]) for r in rows),
            "humanReviewedCases": 0, "independent": False, "annotationHash": digest(review)}


def build_report(observations, labels, directory=DEFAULT_DATASET):
    manifest, cases, _, audit = validate_dataset(directory)
    annotation = validate_assisted(directory, labels)
    require(observations.get("schemaVersion") == "travel-offline-observations-v1"
            and observations.get("datasetHash") == manifest["datasetHash"]
            and observations.get("mode") == "offline_fixtures", "Observation dataset/mode mismatch")
    require(observations.get("upstreamModelRequests") == 0
            and observations.get("externalSourceRequests") == 0, "Unexpected external execution")
    require(observations.get("finishedAt") and observations.get("runId"), "Incomplete run")
    require(observations.get("sourceStableDuringRun") is True
            and isinstance(observations.get("codeSnapshot", {}).get("fileHashes"), dict)
            and observations["codeSnapshot"]["fileHashes"], "Missing/stale execution source fingerprints")
    frozen = {c["caseId"]: c for c in cases}
    raw_rows = observations.get("cases")
    require(isinstance(raw_rows, list) and len(raw_rows) == len(frozen), "Run coverage mismatch")
    labels_by_id = {r["caseId"]: r["annotations"] for r in labels["cases"]}
    rows, seen = [], set()
    for raw in raw_rows:
        cid = raw.get("caseId")
        require(cid in frozen and cid not in seen, "Unknown/duplicate observed case")
        seen.add(cid)
        case = frozen[cid]
        require(raw.get("caseHash") == case["caseHash"] and raw.get("inputHash") == case["inputHash"]
                and raw.get("frozenInput") == case["input"] and raw.get("family") == case["family"],
                "Observed input changed")
        status = raw.get("status")
        if case["family"] == "live_task":
            require(status == "NOT_RUN" and raw.get("reason"), "Live templates cannot be counted as offline passes")
        elif case["family"] == "grounding_replay":
            require(status in {"OBSERVED", "HARNESS_FAILED"}, "Invalid replay status")
            if status == "OBSERVED":
                require(raw.get("structure", {}).get("status") in {"PASS", "FAIL"}
                        and isinstance(raw.get("quality", {}).get("findings"), list)
                        and raw["quality"].get("ruleVersion"), "Missing structural/rule observations")
        else:
            require(status in {"PASS", "HARNESS_FAILED"}, "Invalid fault result")
            resolved = raw.get("resolvedInput")
            require(isinstance(resolved, dict) and all(resolved.get(k) == v for k, v in case["input"].items())
                    and resolved.get("stubCaseId") in {"G01", "G12"}, "Fault input resolution mismatch")
            require(resolved.get("stubItinerary") == frozen[resolved["stubCaseId"]]["input"]["itinerary"],
                    "Fault model fixture mismatch")
            if status == "PASS":
                assertions = raw.get("assertions")
                require(isinstance(assertions, list) and len(assertions) == len(case["assertions"]),
                        "Missing fault assertion coverage")
                expected = {a["id"]: a["description"] for a in case["assertions"]}
                require(len({a.get("id") for a in assertions}) == len(expected), "Duplicate fault assertion")
                for a in assertions:
                    require(a.get("id") in expected and a.get("description") == expected[a["id"]]
                            and a.get("status") == "PASS" and a.get("observedEvidence"),
                            "Fault assertion is not backed by observation")
        item = dict(raw)
        item["resolvedInputHash"] = digest(raw.get("resolvedInput", case["input"]))
        item["assistantAnnotations"] = labels_by_id.get(cid)
        item["labelOrigin"] = "assistant_not_human" if cid in labels_by_id else "no_semantic_annotation"
        item["reviewFlags"] = []
        if case["family"] == "grounding_replay" and status == "OBSERVED":
            warnings = [f for f in raw["quality"]["findings"] if f.get("severity") == "warning"]
            item["warningCount"] = len(warnings)
            source = labels_by_id[cid]["source_support"]["label"]
            if not warnings and source in {"UNSUPPORTED", "CONTRADICTED"}:
                item["reviewFlags"].append("NO_RULE_WARNING_ON_ASSISTANT_UNSUPPORTED")
            source_warnings = [w for w in warnings if w.get("code") != "BUDGET_EXCEEDED"]
            if source_warnings and source == "SUPPORTED":
                item["reviewFlags"].append("RULE_WARNING_ON_ASSISTANT_SUPPORTED")
        rows.append(item)
    replay = [r for r in rows if r["family"] == "grounding_replay"]
    observed = [r for r in replay if r["status"] == "OBSERVED"]
    faults = [r for r in rows if r["family"] == "workflow_fault"]
    return {"schemaVersion": "travel-offline-report-v1", "runId": observations["runId"],
            "datasetHash": manifest["datasetHash"], "observationHash": digest(observations),
            "annotation": annotation, "rendererFileHash": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
            "startedAt": observations.get("startedAt"),
            "finishedAt": observations["finishedAt"], "codeSnapshot": observations.get("codeSnapshot"),
            "inputIntegrityAudit": audit, "counts": {
                "totalCases": len(rows), "replayObserved": len(observed), "replayTotal": len(replay),
                "structureRejected": sum(r["structure"]["status"] == "FAIL" for r in observed),
                "replayWithWarnings": sum(r["warningCount"] > 0 for r in observed),
                "faultPassed": sum(r["status"] == "PASS" for r in faults), "faultTotal": len(faults),
                "notRun": sum(r["status"] == "NOT_RUN" for r in rows),
                "harnessFailed": sum(r["status"] == "HARNESS_FAILED" for r in rows),
                "humanReviewedCases": 0, "assistedDimensions": annotation["filledDimensions"]},
            "reviewFlagCounts": dict(Counter(flag for r in rows for flag in r["reviewFlags"])),
            "semanticAccuracy": None, "falsePositiveRate": None, "falseNegativeRate": None,
            "upstreamModelRequests": 0, "tokens": None, "cost": None,
            "limitations": ["辅助标签非人工金标准，规则与辅助标签的差异仅供复核，不计算语义准确率/误报漏报率。",
                           "固定合成输入和离线故障替身不证明真实模型效果、真实上游可用性或线上重启安全。",
                           "F03为运行中检查点复制及服务重建，并非终止操作系统进程。",
                           "F07在服务/控制器层切换UserContext，未覆盖HTTP认证过滤器。",
                           "输入完整性审计中的evaluatedCases=0指冻结清单原始状态；本轮执行见counts。",
                           "耗时包含本地夹具/文件/PDF开销，不是模型延迟；Token与费用未知。",
                           "合成快照日期在本轮运行日之后，产生SNAPSHOT_TIME_INVALID信息项。规则使用系统时钟，时间类信息可能随运行日期变化；不将其当成语义warning。"],
            "cases": rows}


def render_html(report):
    esc = lambda value: html.escape(str(value), quote=True)
    c = report["counts"]
    blocks = []
    for row in report["cases"]:
        labels = row.get("assistantAnnotations") or {}
        compact = " / ".join(a["label"] for a in labels.values()) or "待运行后评审"
        details = esc(json.dumps(row, ensure_ascii=False, indent=2))
        flags = "；".join(row["reviewFlags"]) or "—"
        blocks.append(f'<article data-family="{esc(row["family"])}"><h2>{esc(row["caseId"])}'
                      f' <span>{esc(row["status"])}</span></h2><p>结构：{esc(row.get("structure",{}).get("status","—"))}'
                      f' · 规则 warning：{esc(row.get("warningCount","—"))} · 耗时：{esc(row["elapsedMs"])} ms</p>'
                      f'<p>AI 辅助标签：{esc(compact)}</p><p>复核线索：{esc(flags)}</p>'
                      f'<details><summary>查看输入、输出、标签理由与证据</summary><pre>{details}</pre></details></article>')
    limitations = "".join("<li>" + esc(x) + "</li>" for x in report["limitations"])
    return f'''<!doctype html><html lang="zh-CN"><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>yangbin AI Agent 离线评测</title>
<style>*{{box-sizing:border-box}}body{{overflow-wrap:anywhere;font:16px/1.6 system-ui,sans-serif;max-width:1120px;margin:32px auto;padding:0 20px;background:#f6f8fc;color:#17263c}}
h1{{font-size:28px}} h2{{font-size:20px;margin:0}} article,.card{{background:white;border:1px solid #d4dce8;border-radius:12px;padding:20px;margin:16px 0}}
span{{font-size:14px;color:#375c85}}pre{{white-space:pre-wrap;overflow-wrap:anywhere;font-size:13px}}.banner{{border-left:5px solid #c98000;padding:12px;background:#fff3d7}}
.metrics{{display:flex;gap:12px;flex-wrap:wrap}}.metrics b{{display:block;font-size:25px}}.metrics .card{{flex:1;min-width:140px}}
select,input{{padding:9px;border:1px solid #8999ac;border-radius:6px;max-width:100%}}details{{border-top:1px solid #dde4ec;padding-top:10px}}small{{overflow-wrap:anywhere}}</style>
<h1>yangbin AI Agent · 离线评测</h1>
<p class="banner">AI 辅助标签已填写，人工金标准仍为 0。这里的通过数是工程故障断言，不是模型准确率。</p>
<div class="metrics"><div class="card"><b>{c["replayObserved"]}/{c["replayTotal"]}</b>固定方案已回放</div>
<div class="card"><b>{c["faultPassed"]}/{c["faultTotal"]}</b>离线故障场景通过</div>
<div class="card"><b>{c["structureRejected"]}</b>结构违规已拒绝</div>
<div class="card"><b>{c["notRun"]}</b>真实任务尚未运行</div></div>
<p>规则告警案例 {c["replayWithWarnings"]}/{c["replayObserved"]}；辅助标签 {c["assistedDimensions"]} 个；执行器失败 {c["harnessFailed"]} 个。</p>
<p>上游模型请求 0；Token / 费用 / 语义准确率：不可用。</p>
<small>runId: {esc(report["runId"])}<br>datasetHash: {esc(report["datasetHash"])}<br>完成于 {esc(report["finishedAt"])}</small>
<p><label>类型 <select id="family"><option value="">全部</option><option>grounding_replay</option><option>workflow_fault</option><option>live_task</option></select></label>
<label> 搜索 <input id="query" placeholder="案例编号、标签或原文"></label></p>
<main>{"".join(blocks)}</main><section class="card"><h2>结果边界</h2><ul>{limitations}</ul></section>
<script>
function filter(){{let f=document.getElementById("family").value,q=document.getElementById("query").value.toLowerCase();
document.querySelectorAll("article").forEach(a=>a.hidden=(f&&a.dataset.family!==f)||!a.textContent.toLowerCase().includes(q));}}
document.getElementById("family").addEventListener("change",filter);document.getElementById("query").addEventListener("input",filter);
</script></html>'''


def write_outputs(report, output):
    output = Path(output).resolve()
    require(not output.is_relative_to(DEFAULT_DATASET.resolve()), "Cannot write into frozen dataset")
    output.mkdir(parents=True, exist_ok=True)
    targets = [output / name for name in ("report.json", "cases.csv", "index.html")]
    require(not any(p.exists() for p in targets), "Reports already exist; use a new output directory")
    with targets[0].open("x", encoding="utf-8") as stream:
        json.dump(report, stream, ensure_ascii=False, indent=2)
    fields = ["caseId", "family", "status", "structure", "warningCount", "labelOrigin",
              "source_support", "constraint_compliance", "uncertainty_disclosure", "reviewFlags", "elapsedMs"]
    with targets[1].open("x", encoding="utf-8-sig", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=fields); writer.writeheader()
        for row in report["cases"]:
            item = {k: row.get(k, "") for k in fields}
            item["structure"] = row.get("structure", {}).get("status", "")
            item["reviewFlags"] = ";".join(row["reviewFlags"])
            for k, value in (row.get("assistantAnnotations") or {}).items():
                item[k] = value["label"]
            writer.writerow(item)
    targets[2].write_text(render_html(report), encoding="utf-8")


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--dataset", type=Path, default=DEFAULT_DATASET)
    p.add_argument("--labels", type=Path, default=DEFAULT_LABELS)
    p.add_argument("--observations", type=Path)
    p.add_argument("--output", type=Path)
    a = p.parse_args()
    try:
        labels = read(a.labels)
        if not a.observations:
            result = validate_assisted(a.dataset, labels)
        else:
            require(a.output is not None, "--output required")
            result = build_report(read(a.observations), labels, a.dataset)
            write_outputs(result, a.output)
            result = {"runId": result["runId"], "counts": result["counts"], "output": str(a.output)}
    except (ExportError, OSError, ValueError, KeyError, TypeError) as error:
        p.exit(2, str(error) + "\n")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
