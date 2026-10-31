"""One bounded, private application-boot rehearsal of the named M025 backup.
Never starts production scripts or inherits production environment/credentials.
Only the calling interpreter and configured Java executable are reused read-only.
"""
import argparse
from contextlib import closing
import http.cookiejar
import importlib.util
import json
import os
from pathlib import Path
import secrets
import shutil
import signal
import socket
import sqlite3
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

from ops_probe import backup_files, digest

BACKUP = 'before-workspace-54a59912'
LIMIT = 512 * 1024 * 1024
DEPLOY_FILES = ('accounts.py', 'gateway_policy.py', 'proxy.py')
OPTIONAL_DEPLOY_FILES = ('desktop_downloads.py',)
READ_PATHS = ('/api/ai/capabilities', '/api/ai/coding/capabilities', '/api/ai/models',
              '/api/ai/manus/conversations', '/api/ai/work/runs')
# Generated only inside the disposable copy. No model/tool POST is routable.
GATEWAY = '''from aiohttp import web
import os
import proxy
READ = %r
@web.middleware
async def restrict(request, handler):
    permitted = ((request.method == 'GET' and request.path in READ + ('/api/auth/me',)) or
                 (request.method == 'POST' and request.path in ('/api/auth/register','/api/auth/login','/api/auth/logout')))
    if not permitted:
        return web.json_response({'blocked':True}, status=403)
    return await handler(request)
app = proxy.create_app()
app.middlewares.insert(1, restrict)  # trace must create request_id before response hooks
web.run_app(app,host='127.0.0.1',port=int(os.environ['PUBLIC_PORT']),access_log=None,print=None)
''' % (READ_PATHS,)


class RehearsalFailure(Exception):
    pass


def check(condition):
    if not condition:
        raise RehearsalFailure('Rehearsal condition failed')


def private_target(root, target):
    root, target = Path(root).resolve(strict=True), Path(target)
    run = root / 'run'
    check(not run.is_symlink() and not target.is_symlink())
    check(target.resolve(strict=True).parent == run and target.name.startswith('restore-'))
    return target


def safe_environment(target, backend_port, gateway_port):
    # Intentionally not dict(os.environ). No inherited Java/Python options,
    # proxy, API keys, Spring config, BYOK overrides, PATH or production HOME.
    return {'HOME': str(target), 'TMPDIR': str(target / 'scratch'),
            'LANG': 'C.UTF-8', 'PYTHONNOUSERSITE': '1', 'PYTHONDONTWRITEBYTECODE': '1',
            'SPRING_PROFILES_ACTIVE': 'prod', 'SERVER_ADDRESS': '127.0.0.1',
            'SERVER_PORT': str(backend_port), 'PUBLIC_BIND': '127.0.0.1',
            'PUBLIC_PORT': str(gateway_port), 'PUBLIC_ORIGIN': 'http://127.0.0.1:' + str(gateway_port),
            'ACCESS_USER': 'restore_admin_unused', 'ACCESS_PASSWORD': secrets.token_urlsafe(32),
            'INTERNAL_API_TOKEN': secrets.token_urlsafe(48), 'SESSION_COOKIE_SECURE': 'false',
            'AGENT_PRODUCTION_MODE': 'false', 'GATEWAY_TLS_MODE': 'off',
            'MODEL_PROVIDER': 'openai', 'MODEL_API_KEY': '', 'DASHSCOPE_API_KEY': '',
            'MODEL_BASE_URL': 'http://127.0.0.1:1', 'RAG_ENABLED': 'false',
            'RAG_CLOUD_ENABLED': 'false', 'MCP_ENABLED': 'false', 'CODING_ENABLED': 'false',
            'CODING_SHELL_ENABLED': 'false', 'TERMINAL_ENABLED': 'false',
            'EXTENDED_TOOLS_ENABLED': 'false', 'PLANNING_LIVE_ENABLED': 'false',
            'SEARCH_API_KEY': '', 'APP_BUILD_REVISION': 'restore-rehearsal-only'}


def inventory(root, backup_name):
    check(backup_name == BACKUP)
    root = Path(root).resolve(strict=True)
    backup = root / 'releases' / backup_name
    check(not (root / 'releases').is_symlink() and not backup.is_symlink())
    check(backup.resolve(strict=True).parent == root / 'releases')
    files, total = backup_files(backup, LIMIT)
    # Old backups predate the download module; newer proxies require their
    # matching module. Copy only the explicitly allowlisted optional files.
    optional = tuple(name for name in OPTIONAL_DEPLOY_FILES if (backup / 'deploy/turing' / name).exists())
    for relative in ('app.jar', *('deploy/turing/' + name for name in DEPLOY_FILES + optional)):
        path = backup / relative
        check(path.is_file() and not path.is_symlink() and path.resolve().is_relative_to(backup))
        check(all(not parent.is_symlink() for parent in path.parents if parent != backup and parent.is_relative_to(backup)))
        files.append(path)
        total += path.stat().st_size
    check(total <= LIMIT)
    hashes = {p.relative_to(backup).as_posix(): digest(p) for p in files}
    manifest_path = backup / 'data-sha256.json'
    check(not manifest_path.is_symlink() and manifest_path.stat().st_size < 8 * 1024 * 1024)
    manifest = json.loads(manifest_path.read_text())
    check(isinstance(manifest, dict))
    check(all(hashes.get(name) == expected for name, expected in manifest.items()))
    return backup, files, hashes, total


def copy_backup(backup, files, hashes, target):
    for source in files:
        relative = source.relative_to(backup)
        if relative.as_posix() == 'secrets.json':
            continue  # No live or backed-up secrets config is needed by either child.
        destination = target / relative
        destination.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        shutil.copyfile(source, destination)
        destination.chmod(0o600)
        check(digest(destination) == hashes[relative.as_posix()])
    for name in ('logs', 'scratch'):
        (target / name).mkdir(exist_ok=True, mode=0o700)
    (target / 'deploy/turing/rehearsal_gateway.py').write_text(GATEWAY, encoding='utf-8')


def original_users(db_path):
    with closing(sqlite3.connect(db_path.as_uri() + '?mode=ro', uri=True)) as db:
        check(db.execute('PRAGMA integrity_check').fetchall() == [('ok',)])
        rows = db.execute('SELECT * FROM users ORDER BY id').fetchall()
        check(any(row[0] == 'admin' for row in rows))
        return rows


def create_copy_invite(target):
    # The offline invitation is the only pre-auth mutation, only in the copy.
    spec = importlib.util.spec_from_file_location('restore_copy_accounts', target / 'deploy/turing/accounts.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    accounts = module.Accounts(target / 'tmp/auth/accounts.sqlite', 'unused_admin', secrets.token_urlsafe(32))
    return accounts.create_invite(1, 1)['code']


def reserve_port():
    reservation = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    reservation.bind(('127.0.0.1', 0))
    return reservation


def stop_children(children):
    for child in reversed(children):
        if child.poll() is None:
            try:
                child.terminate()
            except ProcessLookupError:
                pass  # It exited between poll and terminate; continue cleaning others.
    deadline = time.monotonic() + 10
    for child in reversed(children):
        if child.poll() is None:
            try:
                child.wait(timeout=max(.1, deadline - time.monotonic()))
            except subprocess.TimeoutExpired:
                child.kill()
                child.wait(timeout=3)
    check(all(child.poll() is not None for child in children))


class Client:
    def __init__(self, port):
        self.base = 'http://127.0.0.1:' + str(port)
        self.csrf = ''
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}),
            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()), NoRedirect())

    def request(self, path, method='GET', body=None, csrf=True):
        check(path in READ_PATHS + ('/api/auth/me', '/api/auth/register', '/api/auth/login', '/api/auth/logout', '/api/ai/manus/chat'))
        headers = {'Origin': self.base, 'Content-Type': 'application/json'}
        if csrf:
            headers['X-CSRF-Token'] = self.csrf
        payload = None if body is None else json.dumps(body).encode()
        request = urllib.request.Request(self.base + path, method=method, data=payload, headers=headers)
        try:
            response = self.opener.open(request, timeout=3)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            raw = response.read(1024 * 1024 + 1)
            check(len(raw) <= 1024 * 1024)
            return response.status, json.loads(raw)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def verify_session(client, invite, children, deadline):
    username, password = 'restore_' + secrets.token_hex(5), secrets.token_urlsafe(24)
    for child in children:
        check(child.poll() is None)
    status, registration = client.request('/api/auth/register', 'POST', {'username': username, 'password': password, 'invite': invite})
    check(status == 200 and registration['user']['role'] == 'user')
    client.csrf = registration['user']['csrf']
    check(client.request('/api/auth/logout', 'POST', {})[0] == 200)
    status, login = client.request('/api/auth/login', 'POST', {'username': username, 'password': password})
    check(status == 200 and login['user']['id'] == registration['user']['id'])
    client.csrf = login['user']['csrf']
    check(client.request('/api/auth/logout', 'POST', {}, csrf=False)[0] == 403)
    check(client.request('/api/ai/manus/chat')[0] == 403)
    for path in READ_PATHS:
        check(time.monotonic() < deadline and all(child.poll() is None for child in children))
        status, body = client.request(path)
        check(status == 200)
        if path.endswith('/coding/capabilities'):
            check(body.get('available') is False)
        elif path == '/api/ai/capabilities':
            check(body.get('rag') is False and body.get('terminal') is False and body.get('mcpTools') == [])
        elif path == '/api/ai/models':
            check(isinstance(body.get('profiles'), list))
        else:
            check(body == [])
    check(client.request('/api/auth/logout', 'POST', {})[0] == 200)
    check(client.request('/api/ai/models')[0] == 401)
    return registration['user']['id']


def rehearse(root, backup_name=BACKUP, timeout=280):
    check(os.name == 'posix' and 30 <= timeout <= 280)
    root = Path(root).resolve(strict=True)
    java = Path(json.loads((root / 'secrets.json').read_text())['java'])
    check(java.is_absolute() and java.is_file() and os.access(java, os.X_OK))
    check(Path(sys.executable).is_absolute())
    run = root / 'run'
    check(not run.is_symlink())
    run.mkdir(mode=0o700, exist_ok=True)
    check(run.resolve().parent == root)
    # A failed first attempt never silently becomes a repeated rehearsal.
    marker = run / 'restore-boot-m025-attempt.json'
    with open(marker, 'x', encoding='utf-8') as stream:
        json.dump({'started': True, 'backup': BACKUP}, stream)
    children, target = [], None
    def interrupted(*_):
        raise RehearsalFailure('Interrupted or deadline')
    old_handlers = {number: signal.signal(number, interrupted) for number in (signal.SIGALRM, signal.SIGTERM, signal.SIGINT)}
    signal.alarm(timeout)
    started = time.monotonic()
    try:
        backup, files, hashes, total = inventory(root, backup_name)
        target = Path(tempfile.mkdtemp(prefix='restore-boot-', dir=run))
        private_target(root, target).chmod(0o700)
        copy_backup(backup, files, hashes, target)
        db_path = target / 'tmp/auth/accounts.sqlite'
        users_before = original_users(db_path)
        master_before = digest(target / 'tmp/model-profiles/master.key')
        invite = create_copy_invite(target)
        with closing(reserve_port()) as backend_reservation, closing(reserve_port()) as gateway_reservation:
            backend_port = backend_reservation.getsockname()[1]
            gateway_port = gateway_reservation.getsockname()[1]
            env = safe_environment(target, backend_port, gateway_port)
            backend_reservation.close()
            children.append(subprocess.Popen([str(java), '-Xms32m', '-Xmx256m', '-XX:ActiveProcessorCount=1',
                '-Djava.io.tmpdir=' + str(target / 'scratch'), '-jar', str(target / 'app.jar')],
                cwd=target, env=env, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL))
            gateway_reservation.close()
            children.append(subprocess.Popen([sys.executable, str(target / 'deploy/turing/rehearsal_gateway.py')],
                cwd=target, env=env, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL))
        client = Client(gateway_port)
        deadline = started + timeout
        while time.monotonic() < deadline:
            check(all(child.poll() is None for child in children))
            try:
                status, body = client.request('/api/auth/me')
                if status == 200 and body.get('user', 'missing') is None:
                    # A normal authenticated API below proves this gateway uses
                    # this backend's fresh internal token, never production.
                    import http.client
                    connection = http.client.HTTPConnection('127.0.0.1', backend_port, timeout=2)
                    try:
                        connection.request('GET', '/api/health')
                        response = connection.getresponse()
                        if response.status == 200 and response.read(32) == b'ok':
                            break
                    finally:
                        connection.close()
            except (OSError, ValueError, urllib.error.URLError):
                pass
            time.sleep(.5)
        else:
            raise RehearsalFailure('Not ready')
        created_id = verify_session(client, invite, children, deadline)
        check([row for row in original_users(db_path) if row[0] != created_id] == users_before)
        check(digest(target / 'tmp/model-profiles/master.key') == master_before)
        check(all(digest(backup / name) == expected for name, expected in hashes.items()))
        result = {'applicationBootVerified': True, 'normalRegisterLogin': True, 'csrfVerified': True,
                  'readonlyApiChecks': len(READ_PATHS), 'originalUsersPreserved': True,
                  'originalMasterKeyPreserved': True, 'backupUnchanged': True,
                  'copiedFiles': len(files) - 1, 'sourceBytes': total, 'modelRequests': 0,
                  'productionDataModified': False, 'productionDependenciesUsed': False,
                  'toolExecutionRoutesBlocked': True, 'restoredDefaultToolsStillRegistered': True}
    finally:
        signal.alarm(0)
        try:
            stop_children(children)
        finally:
            # If stopping fails, preserve the private copy for investigation.
            if target is not None and all(child.poll() is not None for child in children):
                shutil.rmtree(private_target(root, target))
            for number, handler in old_handlers.items():
                signal.signal(number, handler)
    result.update(childrenTerminated=True, temporaryCopyRemoved=True,
                  elapsedSeconds=round(time.monotonic() - started, 2))
    marker.write_text(json.dumps({'completed': True, 'result': result}), encoding='utf-8')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path.home() / 'apps/yangbin-ai-agent')
    parser.add_argument('--backup', default=BACKUP, choices=(BACKUP,))
    args = parser.parse_args()
    os.umask(0o077)
    try:
        result = rehearse(args.root, args.backup)
    except Exception:
        print(json.dumps({'applicationBootVerified': False, 'failed': True,
                          'automaticRetry': False, 'detailsSuppressed': True}))
        return 1
    print(json.dumps(result, separators=(',', ':')))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
