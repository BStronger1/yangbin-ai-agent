"""Run a dedicated NarrativePPT API + worker using explicit environment configuration."""
import argparse
import os
from pathlib import Path
import socket
import subprocess
import sys
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True, help="ai-ppt-generator checkout")
    parser.add_argument("--python", type=Path, help="NarrativePPT Python 3.12+ environment")
    parser.add_argument("--runtime", type=Path, required=True, help="separate runtime directory")
    parser.add_argument("--port", type=int, default=39801)
    parser.add_argument("--demo", action="store_true", help="use built-in generators, no paid model")
    parser.add_argument("--migrate", action="store_true", help="upgrade explicitly configured database")
    args = parser.parse_args()
    source = args.source.resolve()
    runtime = args.runtime.resolve()
    python = args.python or source / "backend/.venv" / ("Scripts/python.exe" if os.name == "nt" else "bin/python")
    python = python.resolve()
    if not (source / "frontend/dist-embedded/index.html").is_file():
        parser.error("Run npm run build:embedded in NarrativePPT/frontend first")
    if not python.is_file():
        parser.error("Provide NarrativePPT's installed Python environment via --python")
    for key in ("DATABASE_URL", "REDIS_URL", "AGENT_BRIDGE_SECRET"):
        if not os.environ.get(key):
            parser.error(f"Set {key}; do not reuse an unreviewed standalone database")
    if len(os.environ["AGENT_BRIDGE_SECRET"]) < 32:
        parser.error("AGENT_BRIDGE_SECRET requires at least 32 characters")
    with socket.socket() as sock:
        if sock.connect_ex(("127.0.0.1", args.port)) == 0:
            parser.error("Narrative API port is occupied")
    runtime.mkdir(parents=True, exist_ok=True)
    env = os.environ.copy()
    env.update(PYTHONPATH=str(source / "backend"), AGENT_EMBED_ENABLED="true",
               STORAGE_LOCAL_DIR=str(runtime / "storage"),
               DEMO_MODE="true" if args.demo else "false")
    # Starting outside the source checkout avoids implicitly loading its private .env.
    flags = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
    if args.migrate:
        subprocess.run([str(python), "-m", "alembic", "-c",
                        str(source / "backend/alembic.ini"), "upgrade", "head"],
                       cwd=runtime, env=env, check=True, creationflags=flags)
    commands = {
        "api": [str(python), "-m", "uvicorn", "app.main:app",
                "--host", "127.0.0.1", "--port", str(args.port)],
        "worker": [str(python), str(source / "backend/scripts/run_worker.py")],
    }
    processes, logs = [], []
    try:
        for name, command in commands.items():
            log = (runtime / f"{name}.log").open("a", encoding="utf-8")
            logs.append(log)
            processes.append(subprocess.Popen(command, cwd=runtime, env=env,
                stdout=log, stderr=subprocess.STDOUT, creationflags=flags))
        print(f"NarrativePPT API on 127.0.0.1:{args.port}. Set NARRATIVE_PORT={args.port} "
              "and the same AGENT_BRIDGE_SECRET on the Agent gateway. Ctrl+C stops these processes.",
              flush=True)
        while all(process.poll() is None for process in processes):
            time.sleep(1)
        raise RuntimeError(f"Narrative service stopped; see logs in {runtime}")
    except KeyboardInterrupt:
        pass
    finally:
        for process in processes:
            if process.poll() is None:
                process.terminate()
        for process in processes:
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
        for log in logs:
            log.close()


if __name__ == "__main__":
    main()
