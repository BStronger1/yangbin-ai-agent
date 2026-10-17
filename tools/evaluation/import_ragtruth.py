"""Reproduce a label-blind, source-grouped subset from pinned upstream RAGTruth files."""
import argparse
import hashlib
import json
from pathlib import Path
from datetime import datetime, timezone
from dataset_contract import digest, require, ExportError
COMMIT = "c103204b9ce28d6bbad859304bf30de72b8ed8fe"
HASHES = {
    "LICENSE": "b7fd7d6bdfe0cbba63c63a310914beb4a4acb8bf08da73849219f45385f5b244",
    "dataset/response.jsonl": "e4c2e4ac24fff676d8984cc61c35d791612fadc58015335d97dd632375e18073",
    "dataset/source_info.jsonl": "0dffc26ea9f3c1c3d7c7e8336b56ef1646e3cec876edffcca3c9c624d12d578b",
}
SEED = "yangbin-source-review-20261016-v1"
def rank(value):
    return hashlib.sha256((SEED + "|" + value).encode("utf-8")).hexdigest()
def select(sources, responses):
    groups = {}
    for response in responses:
        if response["split"] == "test" and response["quality"] == "good":
            groups.setdefault(response["source_id"], []).append(response)
    selected = []
    for task in ("QA", "Summary", "Data2txt"):
        available = [s for s in sources if s["task_type"] == task and s["source_id"] in groups]
        require(len(available) >= 8, "Insufficient eligible source groups")
        for source in sorted(available, key=lambda s: rank(task + "|" + s["source_id"]))[:8]:
            response = min(groups[source["source_id"]], key=lambda r: rank("response|" + r["id"]))
            selected.append({"caseId": "RGT-" + response["id"], "sourceRecord": source,
                             "responseRecord": response, "caseHash": digest({"source": source, "response": response})})
    return selected
def freeze(upstream, output):
    for name, expected in HASHES.items():
        require(hashlib.sha256((upstream / name).read_bytes()).hexdigest() == expected, "Upstream hash mismatch: " + name)
    sources = [json.loads(line) for line in (upstream / "dataset/source_info.jsonl").read_text(encoding="utf-8").splitlines()]
    responses = [json.loads(line) for line in (upstream / "dataset/response.jsonl").read_text(encoding="utf-8").splitlines()]
    cases = select(sources, responses)
    output.mkdir(parents=True, exist_ok=False)
    (output / "LICENSE").write_bytes((upstream / "LICENSE").read_bytes())
    (output / "cases.json").write_text(json.dumps({"schemaVersion": "ragtruth-subset-v1", "cases": cases}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    files = {name: hashlib.sha256((output / name).read_bytes()).hexdigest() for name in ("LICENSE", "cases.json")}
    manifest = {"schemaVersion": "ragtruth-subset-manifest-v1", "datasetId": "ragtruth-test-source24-v1",
                "upstream": "https://github.com/ParticleMedia/RAGTruth", "upstreamCommit": COMMIT,
                "upstreamFiles": HASHES, "createdAt": datetime.now(timezone.utc).isoformat(),
                "seed": SEED, "selection": "test + quality=good; 8 source groups per task by SHA256 rank, 1 response per source by SHA256 rank; no label balancing or detector-result filtering",
                "taskCounts": {"QA": 8, "Summary": 8, "Data2txt": 8}, "caseCount": len(cases),
                "labelProvenance": "upstream_human_annotations_unchanged", "files": files, "datasetHash": digest(files),
                "interpretation": "External small diagnostic subset, not representative corpus accuracy; English/general evidence differs from Chinese travel snapshots."}
    (output / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return manifest
if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--upstream", type=Path, required=True)
    p.add_argument("--output", type=Path, required=True)
    a = p.parse_args()
    try: print(json.dumps(freeze(a.upstream, a.output), ensure_ascii=False, indent=2))
    except (ExportError, OSError) as e: p.exit(2, str(e) + "\n")
