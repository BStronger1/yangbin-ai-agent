#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
if tmux has-session -t '=yangbin-ai-agent' 2>/dev/null; then
  echo "yangbin-ai-agent already running"; exit 0
fi
test -f "$ROOT/secrets.json" && test -f "$ROOT/app.jar"
tmux new-session -d -s yangbin-ai-agent -c "$ROOT" "exec '$ROOT/runtime/venv/bin/python' '$ROOT/deploy/turing/service.py'"
echo "Started yangbin-ai-agent. Check /api/health with your access credentials."
