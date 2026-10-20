"""Optional loopback gateway for a named Cloudflare Tunnel; no secret CLI arguments."""
import argparse
import json
import os
import shlex
import subprocess
import time
from pathlib import Path
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[2]
SESSION = 'yangbin-ai-agent-public'


def public_environment(root):
    settings = json.loads((root / 'public-gateway.json').read_text(encoding='utf-8-sig'))
    origin = settings.get('origin', '')
    parsed = urlsplit(origin)
    if (parsed.scheme != 'https' or not parsed.hostname or parsed.username or
            parsed.password or parsed.path or parsed.query or parsed.fragment or
            parsed.port not in (None, 443)):
        raise ValueError('Public gateway requires an exact HTTPS origin on port 443')
    if settings.get('port', 18826) != 18826:
        raise ValueError('Public gateway uses reserved loopback port 18826')
    if not (root / 'tmp/auth/accounts.sqlite').is_file():
        raise ValueError('Start the main installation to provision accounts first')
    env = dict(os.environ, **json.loads((root / 'secrets.json').read_text(encoding='utf-8-sig'))['env'])
    env.update(PUBLIC_ORIGIN=origin, PUBLIC_BIND='127.0.0.1', PUBLIC_PORT='18826',
               SESSION_COOKIE_SECURE='true', AGENT_PRODUCTION_MODE='true',
               GATEWAY_TLS_MODE='reverse-proxy')
    return env


def running():
    return subprocess.run(['tmux', 'has-session', '-t', '=' + SESSION],
                          stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0


def stop():
    if not running():
        return
    subprocess.run(['tmux', 'send-keys', '-t', SESSION + ':0.0', 'C-c'], check=True)
    deadline = time.monotonic() + 60
    while running():
        if time.monotonic() >= deadline:
            raise RuntimeError('Public gateway did not stop; no forced kill performed')
        time.sleep(.25)


def start(root=ROOT):
    if running():
        return
    env = public_environment(root)
    command = shlex.join([str(root / 'runtime/venv/bin/python'),
                         str(root / 'deploy/turing/public_gateway.py'), 'run'])
    subprocess.run(['tmux', 'new-session', '-d', '-s', SESSION, '-c', str(root),
                    'exec ' + command], check=True)
    from readiness import gateway_ready
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        if not running():
            raise RuntimeError('Public gateway exited during startup')
        try:
            if gateway_ready(env, timeout=2):
                return
        except (OSError, ValueError):
            pass
        time.sleep(.25)
    stop()
    raise RuntimeError('Public gateway readiness timed out')


def run(root=ROOT):
    os.umask(0o077)
    os.environ.update(public_environment(root))
    # proxy reads backend settings on import, so configure the environment first.
    from aiohttp import web
    from gateway_policy import configure_log
    from proxy import create_app
    configure_log(root, 'gateway-public.jsonl')
    app = create_app()
    web.run_app(app, host='127.0.0.1', port=18826, access_log=None,
                shutdown_timeout=30, print=None)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('start', 'stop', 'status', 'run'))
    action = parser.parse_args().action
    try:
        if action == 'status':
            print(json.dumps({'running': running(), 'bind': '127.0.0.1', 'port': 18826}))
        else:
            {'start': start, 'stop': stop, 'run': run}[action]()
    except Exception:
        raise SystemExit('Public gateway operation failed; inspect deployment configuration privately')
