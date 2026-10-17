"""Run the frozen offline suite and render its bound report; no server or model credentials required."""
import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import uuid
from dataset_contract import REPO, DEFAULT_DATASET, validate_dataset, read, ExportError, require
from offline_report import DEFAULT_LABELS, validate_assisted, build_report, write_outputs


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--maven-settings", type=Path)
    args = parser.parse_args()
    validate_dataset(DEFAULT_DATASET)
    labels = read(DEFAULT_LABELS)
    validate_assisted(DEFAULT_DATASET, labels)
    mvn = shutil.which("mvn")
    require(mvn is not None, "Maven not found on PATH")
    command = [mvn, "-B", "-ntp", "-Dtest=FrozenDatasetEvaluationTest"]
    if args.maven_settings:
        settings = str(args.maven_settings.resolve())
        command += ["-gs", settings, "-s", settings]
    command += ["test"]
    result = subprocess.run(command, cwd=REPO, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            encoding="utf-8", errors="replace", timeout=600)
    logs = REPO / "target/evaluation-launch-logs"
    logs.mkdir(parents=True, exist_ok=True)
    log = logs / (str(uuid.uuid4()) + ".log")
    log.write_text(result.stdout, encoding="utf-8")
    match = re.search(r"Frozen evaluation observations: (.+)", result.stdout)
    require(match is not None, "Evaluation did not produce observations. Inspect " + str(log))
    observed = Path(match.group(1).strip()).resolve()
    require(observed.is_relative_to((REPO / "target/evaluation").resolve()), "Unexpected observation path")
    report = build_report(read(observed), labels)
    output = observed.parent / "report"
    write_outputs(report, output)
    print("Run:", report["runId"])
    print("Counts:", report["counts"])
    print("Dashboard:", output / "index.html")
    print("Log:", log)
    return 1 if result.returncode or report["counts"]["harnessFailed"] else 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (ExportError, OSError, subprocess.SubprocessError) as error:
        sys.exit(str(error))
