#!/usr/bin/env bash
# Main LAN application only. Public gateway and Tunnel need separate explicit activation.
set -u
PATH=/usr/local/bin:/usr/bin:/bin
export PATH
ROOT="${1:-${HOME}/apps/yangbin-ai-agent}"
# NFS may become visible before all runtime files are ready.
for attempt in $(seq 1 30); do
  if [[ -r "$ROOT/secrets.json" && -r "$ROOT/app.jar" && -r "$ROOT/deploy/turing/start.sh" && -x "$ROOT/runtime/venv/bin/python" ]]; then
    mkdir -p -- "$ROOT/run" || exit 1
    chmod 700 "$ROOT/run" || exit 1
    (
      flock -n 9 || exit 0
      /bin/bash "$ROOT/deploy/turing/start.sh" >/dev/null 2>&1
    ) 9>"$ROOT/run/main-boot.lock" && exit 0
  fi
  sleep 10
done
exit 1
