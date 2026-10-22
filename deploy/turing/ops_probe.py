"""Model-free single-installation probes. No restarts, credentials or notifications.
health checks fixed loopback and configured local listener endpoints; load runs <=60 backend health requests.
restore-check rehearses a stopped-service backup in a private temporary copy.
It never overwrites live data and does not prove application boot or freshness.
"""
import argparse
from contextlib import closing
from concurrent.futures import ThreadPoolExecutor
import hashlib
import http.client
import json
import math
import os
from pathlib import Path
import shutil
import sqlite3
import socket
import ipaddress
from readiness import listener
import stat
import tempfile
import time

TARGETS = {'backend': (18126, '/api/health'), 'gateway': (18825, '/api/auth/me'),
           'publicGateway': (18826, '/api/auth/me'), 'tunnel': (18829, '/ready')}


def probe(name, timeout=2, gateway_address=None):
    port, path = TARGETS[name]
    host = '127.0.0.1'
    if name == 'gateway' and gateway_address is not None:
        host, port = gateway_address
    started = time.monotonic()
    connection = http.client.HTTPConnection(host, port, timeout=timeout)
    status, healthy = None, False
    try:
        connection.request('GET', path)
        response = connection.getresponse()
        status = response.status
        body = response.read(65537)
        if status == 200 and len(body) <= 65536:
            if name == 'backend':
                healthy = body.strip() == b'ok'
            else:
                data = json.loads(body)
                if name == 'tunnel':
                    count = data.get('readyConnections') if isinstance(data, dict) else None
                    healthy = type(count) is int and count > 0
                else:
                    healthy = isinstance(data, dict) and 'user' in data and data['user'] is None
    except Exception:
        pass  # No exception strings or response bodies enter diagnostics.
    finally:
        connection.close()
    return {'component': name, 'healthy': healthy, 'status': status,
            'durationMs': round((time.monotonic() - started) * 1000, 2)}


def configured_gateway(root):
    # Keep all credentials in process. Only the listener tuple leaves this scope.
    config = json.loads((Path(root) / 'secrets.json').read_text(encoding='utf-8'))
    env = config.get('env', {})
    address = listener({name: env[name] for name in ('PUBLIC_BIND', 'PUBLIC_PORT') if name in env})
    ip = ipaddress.ip_address(address[0])
    if ip.is_multicast or ip.is_unspecified:
        raise ValueError('Invalid local listener')
    # Binding an ephemeral socket proves interface ownership; never connect to
    # an arbitrary remote IP supplied through configuration. No listen occurs.
    family = socket.AF_INET6 if ip.version == 6 else socket.AF_INET
    with socket.socket(family, socket.SOCK_STREAM) as local:
        local.bind((address[0], 0))
    return address


def health(root):
    results = []
    for name in TARGETS:
        if name == 'gateway':
            try:
                address = configured_gateway(root)
                results.append(probe(name, gateway_address=address))
            except Exception:
                results.append({'component': name, 'healthy': False, 'status': None, 'durationMs': 0})
        else:
            results.append(probe(name))
    return {'healthy': all(row['healthy'] for row in results), 'checks': results,
            'scope': 'local-listeners-no-model', 'externalTlsVerified': False}


def alert_transition(previous, report, failure_threshold=3):
    """Pure local debounce: one firing event after N failures, one recovery."""
    if not 1 <= failure_threshold <= 10:
        raise ValueError('Invalid failure threshold')
    state, events = {}, []
    for row in report['checks']:
        name = row['component']
        if name not in TARGETS:
            raise ValueError('Invalid component')
        prior = previous.get(name, {})
        failures = 0 if row['healthy'] else min(int(prior.get('failures', 0)) + 1, failure_threshold)
        firing = failures >= failure_threshold
        if firing != bool(prior.get('firing', False)):
            events.append({'component': name, 'event': 'firing' if firing else 'resolved'})
        state[name] = {'failures': failures, 'firing': firing}
    return state, events



def monitor(root):
    """One poll; persist only aggregates. Scheduling is an explicit separate step."""
    root = Path(root).resolve(strict=True)
    run = root / 'run'
    if run.is_symlink():
        raise ValueError('Invalid monitor directory')
    run.mkdir(mode=0o700, exist_ok=True)
    state_file = run / 'ops-health.json'
    lock = run / 'ops-health.lock'
    if state_file.is_symlink() or lock.is_symlink():
        raise ValueError('Invalid monitor files')
    descriptor = os.open(lock, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    os.close(descriptor)
    try:
        previous = {}
        if state_file.exists():
            if state_file.stat().st_size > 65536:
                raise ValueError('Invalid monitor state')
            previous = json.loads(state_file.read_text(encoding='utf-8'))['state']
        report = health(root)
        state, events = alert_transition(previous, report)
        current = {'state': state, 'report': report, 'observedAt': int(time.time())}
        fd, temporary = tempfile.mkstemp(prefix='ops-health-', suffix='.tmp', dir=run)
        try:
            with os.fdopen(fd, 'w', encoding='utf-8') as stream:
                json.dump(current, stream, separators=(',', ':'))
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, state_file)
        finally:
            Path(temporary).unlink(missing_ok=True)
        return {'healthy': report['healthy'], 'events': events,
                'scope': 'local-transition-alerts-only', 'externallyDelivered': False}
    finally:
        lock.unlink()


def bounded_load(requests=24, concurrency=2):
    if type(requests) is not int or not 1 <= requests <= 60:
        raise ValueError('Request limit is 1..60')
    if type(concurrency) is not int or not 1 <= concurrency <= 4:
        raise ValueError('Concurrency limit is 1..4')
    started = time.monotonic()
    with ThreadPoolExecutor(max_workers=concurrency) as pool:
        results = list(pool.map(lambda _: probe('backend'), range(requests)))
    durations = sorted(row['durationMs'] for row in results)
    errors = sum(not row['healthy'] for row in results)
    return {'scope': 'backend-health-only-not-model-capacity', 'requests': requests,
            'concurrency': concurrency, 'errors': errors, 'errorRate': errors / requests,
            'p95Ms': durations[math.ceil(requests * .95) - 1], 'maxMs': max(durations),
            'elapsedSeconds': round(time.monotonic() - started, 3), 'modelRequests': 0}


def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(chunk)
    return value.hexdigest()


def backup_files(backup, max_bytes=512 * 1024 * 1024):
    files, total = [], 0
    for name in ('tmp', 'knowledge', 'secrets.json'):
        source = backup / name
        if not source.exists() and not source.is_symlink():
            if name == 'knowledge':
                continue
            raise ValueError('Required backup data missing')
        candidates = [source]
        if source.is_dir() and not source.is_symlink():
            candidates.extend(source.rglob('*'))
        for candidate in candidates:
            mode = candidate.lstat().st_mode
            if stat.S_ISLNK(mode) or not (stat.S_ISREG(mode) or stat.S_ISDIR(mode)):
                raise ValueError('Backup links or special files are unsupported')
            if stat.S_ISREG(mode):
                total += candidate.stat().st_size
                files.append(candidate)
                if total > max_bytes or len(files) > 20000:
                    raise ValueError('Backup exceeds bounded rehearsal limit')
    required = ('tmp/auth/accounts.sqlite', 'tmp/model-profiles/master.key')
    if any(not (backup / name).is_file() for name in required):
        raise ValueError('Account database or model key missing')
    return files, total


def restore_check(root, backup_name):
    root = Path(root).resolve(strict=True)
    if not backup_name.startswith('before-workspace-') or any(c not in 'abcdefghijklmnopqrstuvwxyz0123456789-' for c in backup_name):
        raise ValueError('Invalid backup name')
    releases = root / 'releases'
    backup = releases / backup_name
    if releases.is_symlink() or backup.is_symlink() or backup.resolve(strict=True).parent != releases:
        raise ValueError('Backup outside application releases')
    files, total = backup_files(backup)
    manifest_file = backup / 'data-sha256.json'
    if manifest_file.is_symlink() or manifest_file.stat().st_size > 8 * 1024 * 1024:
        raise ValueError('Invalid backup manifest')
    manifest = json.loads(manifest_file.read_text(encoding='utf-8'))
    if not isinstance(manifest, dict):
        raise ValueError('Invalid backup manifest')
    source_hashes = {path.relative_to(backup).as_posix(): digest(path) for path in files}
    if any(name not in source_hashes or source_hashes[name] != expected for name, expected in manifest.items()):
        raise ValueError('Backup manifest mismatch')
    run = root / 'run'
    if run.is_symlink():
        raise ValueError('Run directory must not be a symlink')
    run.mkdir(mode=0o700, exist_ok=True)
    if run.resolve().parent != root:
        raise ValueError('Invalid rehearsal parent')
    with tempfile.TemporaryDirectory(prefix='restore-check-', dir=run) as temporary:
        target = Path(temporary)
        if target.resolve().parent != run.resolve():
            raise ValueError('Invalid rehearsal target')
        target.chmod(0o700)
        for source in files:
            destination = target / source.relative_to(backup)
            destination.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
            shutil.copyfile(source, destination)
            destination.chmod(0o600)
            if digest(destination) != source_hashes[source.relative_to(backup).as_posix()]:
                raise ValueError('Restored bytes differ')
        json_count = 0
        for source in files:
            relative = source.relative_to(backup)
            if relative.suffix == '.json':
                json.loads((target / relative).read_text(encoding='utf-8'))
                json_count += 1
        account_db = target / 'tmp/auth/accounts.sqlite'
        with closing(sqlite3.connect(account_db.as_uri() + '?mode=ro', uri=True)) as db:
            if db.execute('PRAGMA integrity_check').fetchall() != [('ok',)]:
                raise ValueError('Restored account integrity check failed')
            tables = {row[0] for row in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
            if not {'users', 'sessions', 'invites'}.issubset(tables):
                raise ValueError('Restored account schema incomplete')
        if (target / 'tmp/model-profiles/master.key').stat().st_size != 32:
            raise ValueError('Restored encryption key invalid')
    return {'restored': True, 'copiedFiles': len(files), 'copiedBytes': total,
            'jsonValidated': json_count, 'sqliteIntegrity': True,
            'originalManifestFiles': len(manifest),
            'filesOutsideOriginalManifest': len(files) - len(manifest),
            'temporaryCopyRemoved': True, 'applicationBootVerified': False, 'liveDataModified': False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('health', 'monitor', 'load', 'restore-check'))
    parser.add_argument('--requests', type=int, default=24)
    parser.add_argument('--concurrency', type=int, default=2)
    parser.add_argument('--root', type=Path, default=Path.home() / 'apps/yangbin-ai-agent')
    parser.add_argument('--backup')
    args = parser.parse_args()
    os.umask(0o077)
    try:
        if args.command == 'health':
            result = health(args.root)
            success = result['healthy']
        elif args.command == 'monitor':
            result = monitor(args.root)
            success = result['healthy']
        elif args.command == 'load':
            result = bounded_load(args.requests, args.concurrency)
            success = result['errors'] == 0
        else:
            result = restore_check(args.root, args.backup or '')
            success = result['restored']
    except Exception:
        result, success = {'success': False, 'error': 'Probe failed; inspect configuration or backup privately'}, False
    print(json.dumps(result, separators=(',', ':')))
    return 0 if success else 1


if __name__ == '__main__':
    raise SystemExit(main())
