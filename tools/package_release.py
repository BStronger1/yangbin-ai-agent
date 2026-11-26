"""Build and verify the private server release from the tested repair-pairs tree."""
from hashlib import sha256
from pathlib import Path
import gzip
import io
import json
import re
import subprocess
import tarfile
import zipfile

import argparse
args = argparse.ArgumentParser()
args.add_argument("--out", required=True, type=Path)
args.add_argument("--label", default="v040-20261127")
options = args.parse_args()
OUT = options.out.resolve()
OUT.mkdir(parents=True, exist_ok=True)
REPO = Path(__file__).resolve().parents[1]
WEB = REPO / "yangbin-ai-agent-frontend"
RUNTIME = REPO / "coding-agent"


def digest(path):
    with path.open("rb") as stream:
        import hashlib
        return hashlib.file_digest(stream, "sha256").hexdigest()


def walk(directory):
    return (p for p in sorted(directory.rglob("*")) if p.is_file() and "__pycache__" not in p.parts)


def info(value):
    return json.dumps(value, sort_keys=True, indent=2).encode() + b"\n"


def main():
    jar = REPO / "target/yangbin-ai-agent-0.0.1-SNAPSHOT.jar"
    files = {"app.jar": jar}
    with zipfile.ZipFile(jar) as z:
        assert z.testzip() is None
        classes = set(z.namelist())
        for name in ("blog/BlogService", "controller/BlogController", "controller/PublicBlogController", "coding/LocalCandidateReceipts", "controller/WorkEvidenceController", "work/WorkEvidenceService"):
            assert "BOOT-INF/classes/com/yangbin/yangbinaiagent/" + name + ".class" in classes
        assert not any(n.endswith("/SweCampaign.class") for n in classes)
    for path in walk(REPO / "deploy/turing"):
        if path.suffix in (".py", ".sh", ".md", ".txt", ".example"):
            files[path.relative_to(REPO).as_posix()] = path
    for path in walk(WEB / "dist"):
        files["frontend/" + path.relative_to(WEB / "dist").as_posix()] = path
    assert all(list((WEB / "dist/assets").glob(stem + "-*.js")) for stem in ("BlogIndex", "BlogPost", "PublicPost", "CodingWorkspace"))
    runtime = {n: RUNTIME / n for n in ("package.json", "package-lock.json", "PI-LICENSE", "README.md")}
    for folder in ("src", "test"):
        for path in walk(RUNTIME / folder):
            if path.name != "historical-freeze.test.mjs":
                runtime[path.relative_to(RUNTIME).as_posix()] = path
    source = {}
    for directory in (REPO / "src/main", WEB / "src", WEB / "public"):
        for path in walk(directory):
            source[path.relative_to(REPO).as_posix()] = digest(path)
    for path in (REPO / "pom.xml", WEB / "index.html", WEB / "package.json", WEB / "package-lock.json", WEB / "vite.config.js"):
        source[path.relative_to(REPO).as_posix()] = digest(path)
    for path in [*files.values(), *runtime.values()]:
        assert path.is_file() and not path.is_symlink() and path.resolve().is_relative_to(REPO)
        assert not any(part in ("secrets.json", ".env", "node_modules", ".git") for part in path.parts)
        if path.suffix == ".sh":
            assert b"\r" not in path.read_bytes()
    hashes = {n: digest(p) for n, p in files.items()}
    runtime_hashes = {n: digest(p) for n, p in runtime.items()}
    release_id = options.label + "-" + sha256(info([hashes, runtime_hashes])).hexdigest()[:12]
    prefix = "runtime/coding-releases/" + release_id
    files.update({prefix + "/" + n: p for n, p in runtime.items()})
    hashes.update({prefix + "/" + n: h for n, h in runtime_hashes.items()})
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=REPO, text=True).strip()
    manifest = {"releaseId": release_id, "commit": commit, "sourceInputsSha256": sha256(info(source)).hexdigest(),
                "workingTree": bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=REPO)),
                "codingRuntime": {"path": prefix}, "sha256": hashes}
    manifest_bytes = info(manifest)
    archive = OUT / "agent-release.tar.gz"
    pending = archive.with_suffix(".pending")
    with pending.open("wb") as raw, gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0, compresslevel=3) as gz, tarfile.open(fileobj=gz, mode="w") as bundle:
        for name in sorted([*files, "release.json"]):
            record = tarfile.TarInfo(name)
            record.mode = 0o755 if name.endswith(".sh") else 0o644
            if name == "release.json":
                record.size = len(manifest_bytes)
                bundle.addfile(record, io.BytesIO(manifest_bytes))
            else:
                record.size = files[name].stat().st_size
                with files[name].open("rb") as data:
                    bundle.addfile(record, data)
    with tarfile.open(pending, mode="r|gz") as bundle:
        visited = set()
        for member in bundle:
            assert member.isfile() and member.name not in visited
            visited.add(member.name)
            actual = sha256(bundle.extractfile(member).read()).hexdigest()
            assert actual == (sha256(manifest_bytes).hexdigest() if member.name == "release.json" else hashes[member.name])
        assert visited == set(files) | {"release.json"}
    pending.replace(archive)
    (OUT / "release.json").write_bytes(manifest_bytes)
    (OUT / "source-inputs.json").write_bytes(info(source))
    result = {"releaseId": release_id, "archive": archive.name, "sha256": digest(archive), "bytes": archive.stat().st_size,
              "files": len(files), "runtimeFiles": len(runtime), "workingTree": manifest["workingTree"], "sourceCommit": commit}
    (OUT / "package-report.json").write_bytes(info(result))
    print(info(result).decode())


if __name__ == "__main__":
    main()
