#!/usr/bin/env bash
set -euo pipefail
if tmux has-session -t '=yangbin-ai-agent' 2>/dev/null; then
  tmux send-keys -t 'yangbin-ai-agent:0.0' C-c
  echo "Shutdown requested for yangbin-ai-agent"
fi
