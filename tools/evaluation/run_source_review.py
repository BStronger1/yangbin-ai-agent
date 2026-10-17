"""Execute the bounded claim checker, frozen travel replay and external applicability audit."""
import argparse
import re
import shutil
import subprocess
import sys
import uuid
from pathlib import Path
from dataset_contract import REPO, read, validate_dataset, require, ExportError
from offline_report import DEFAULT_LABELS
from source_review_report import CONFIRMATION, validate_confirmation, external_audit, compare, write

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--maven-settings", type=Path)
    a = p.parse_args()
    validate_dataset()
    validate_confirmation(read(DEFAULT_LABELS), read(CONFIRMATION))
    external_audit()
    executable = shutil.which("mvn")
    require(executable is not None, "Maven not found")
    command = [executable, "-B", "-ntp", "-Dtest=SourceClaimReviewTest,FrozenDatasetEvaluationTest"]
    if a.maven_settings:
        command += ["-gs", str(a.maven_settings.resolve()), "-s", str(a.maven_settings.resolve())]
    result = subprocess.run(command + ["test"], cwd=REPO, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            encoding="utf-8", errors="replace", timeout=600)
    logs = REPO / "target/source-review-launch-logs"; logs.mkdir(parents=True, exist_ok=True)
    log = logs / (str(uuid.uuid4()) + ".log"); log.write_text(result.stdout, encoding="utf-8")
    require(result.returncode == 0, "Java validation failed; raw evidence retained. Log: " + str(log))
    observed = re.search(r"Frozen evaluation observations: (.+)", result.stdout)
    external = re.search(r"External applicability report: (.+)", result.stdout)
    require(observed and external, "Missing execution artifacts. Log: " + str(log))
    raw = Path(observed.group(1).strip()).resolve()
    outside = Path(external.group(1).strip()).resolve()
    require(raw.is_relative_to((REPO / "target/evaluation").resolve())
            and outside.is_relative_to((REPO / "target/source-review").resolve()), "Unexpected artifact path")
    report = compare(read(raw), read(outside))
    write(report, raw.parent / "source-review")
    print("Run:", report["runId"])
    print("Baseline/Candidate true positives:", report["baseline"]["truePositive"], report["candidate"]["truePositive"])
    print("External coverage: 0/24 (all abstained)")
    print("Observations:", raw)
    print("External:", outside)
    print("Dashboard:", raw.parent / "source-review/index.html")
    print("Log:", log)

if __name__ == "__main__":
    try: main()
    except (ExportError, OSError, subprocess.SubprocessError) as e: sys.exit(str(e))
