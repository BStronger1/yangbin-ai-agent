from contextlib import closing
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import Mock, patch

import restore_boot_m025 as restore


class RestoreBootTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.backup = self.root / 'releases' / restore.BACKUP
        (self.backup / 'tmp/auth').mkdir(parents=True)
        (self.backup / 'tmp/model-profiles').mkdir()
        with closing(sqlite3.connect(self.backup / 'tmp/auth/accounts.sqlite')) as db:
            db.executescript('''CREATE TABLE users(id TEXT PRIMARY KEY,username TEXT UNIQUE NOT NULL,password TEXT NOT NULL,role TEXT NOT NULL,active INTEGER NOT NULL DEFAULT 1,created REAL NOT NULL);
CREATE TABLE sessions(token TEXT PRIMARY KEY,user_id TEXT NOT NULL,csrf TEXT NOT NULL,expires REAL NOT NULL);
CREATE TABLE invites(id TEXT PRIMARY KEY,code TEXT UNIQUE NOT NULL,uses INTEGER NOT NULL DEFAULT 0,max_uses INTEGER NOT NULL,expires REAL NOT NULL,revoked INTEGER NOT NULL DEFAULT 0,created REAL NOT NULL);
INSERT INTO users VALUES('admin','originaladmin','unused-password','admin',1,0);''')
        (self.backup / 'tmp/model-profiles/master.key').write_bytes(b'x' * 32)
        (self.backup / 'secrets.json').write_text('{"private":"not-for-child"}')
        (self.backup / 'app.jar').write_bytes(b'fixture-jar')
        (self.backup / 'deploy/turing').mkdir(parents=True)
        for name in restore.DEPLOY_FILES:
            shutil.copyfile(Path(__file__).parent / name, self.backup / 'deploy/turing' / name)
        (self.backup / 'data-sha256.json').write_text('{}')
        (self.root / 'run').mkdir()
        self.target = self.root / 'run/restore-test'
        self.target.mkdir()

    def test_environment_is_clean_and_no_production_keys_or_home(self):
        with patch.dict(os.environ, {'MODEL_API_KEY':'do-not-inherit', 'JAVA_TOOL_OPTIONS':'bad',
                                     'PYTHONPATH':'bad', 'HTTPS_PROXY':'bad', 'HOME':'production'}):
            env = restore.safe_environment(self.target, 32101, 32102)
        self.assertEqual(env['MODEL_API_KEY'], '')
        self.assertEqual(env['HOME'], str(self.target))
        for name in ('JAVA_TOOL_OPTIONS','PYTHONPATH','HTTPS_PROXY','MODEL_PROFILE_MASTER_KEY','CODING_NODE'):
            self.assertNotIn(name, env)
        for name in ('MCP_ENABLED','CODING_ENABLED','RAG_ENABLED','TERMINAL_ENABLED','EXTENDED_TOOLS_ENABLED'):
            self.assertEqual(env[name], 'false')
        self.assertNotIn('do-not-inherit', json.dumps(env))
        self.assertEqual(env['SERVER_ADDRESS'], '127.0.0.1')

    def test_inventory_exact_backup_and_no_unbounded_copy(self):
        backup, files, hashes, total = restore.inventory(self.root, restore.BACKUP)
        self.assertEqual(backup, self.backup)
        self.assertTrue(total > 0)
        self.assertEqual(len(hashes), len(files))
        with self.assertRaises(restore.RehearsalFailure):
            restore.inventory(self.root, 'before-workspace-other')
        with patch.object(restore, 'LIMIT', 1):
            with self.assertRaises(ValueError):
                restore.inventory(self.root, restore.BACKUP)

    def test_inventory_detects_corruption_before_copy(self):
        (self.backup / 'data-sha256.json').write_text('{"app.jar":"corrupted"}')
        with self.assertRaises(restore.RehearsalFailure):
            restore.inventory(self.root, restore.BACKUP)

    def test_copy_excludes_secrets_keeps_users_and_key(self):
        backup, files, hashes, _ = restore.inventory(self.root, restore.BACKUP)
        users = restore.original_users(self.backup / 'tmp/auth/accounts.sqlite')
        restore.copy_backup(backup, files, hashes, self.target)
        self.assertFalse((self.target / 'secrets.json').exists())
        self.assertEqual(restore.original_users(self.target / 'tmp/auth/accounts.sqlite'), users)
        self.assertEqual((self.target / 'tmp/model-profiles/master.key').read_bytes(), b'x' * 32)
        self.assertTrue((self.target / 'deploy/turing/rehearsal_gateway.py').is_file())
        self.assertTrue(all(restore.digest(backup / name) == expected for name, expected in hashes.items()))

    def test_copy_detects_changed_source(self):
        backup, files, hashes, _ = restore.inventory(self.root, restore.BACKUP)
        (self.backup / 'app.jar').write_bytes(b'changed')
        with self.assertRaises(restore.RehearsalFailure):
            restore.copy_backup(backup, files, hashes, self.target)

    def test_cleanup_target_rejects_live_root_and_wrong_prefix(self):
        self.assertEqual(restore.private_target(self.root, self.target), self.target)
        for target in (self.root, self.backup, self.root / 'run'):
            with self.assertRaises(restore.RehearsalFailure):
                restore.private_target(self.root, target)

    def test_stop_terminates_only_supplied_children(self):
        exited = Mock(); exited.poll.return_value = 0
        running = Mock(); running.poll.side_effect = [None, None, 0]
        restore.stop_children([exited, running])
        exited.terminate.assert_not_called()
        running.terminate.assert_called_once()
        running.wait.assert_called_once()


    def test_stop_escalates_only_its_own_unresponsive_child(self):
        child = Mock()
        child.poll.side_effect = [None, None, 0]
        child.wait.side_effect = [subprocess.TimeoutExpired('own-child',10), 0]
        restore.stop_children([child])
        child.terminate.assert_called_once()
        child.kill.assert_called_once()

    def test_stop_survives_exit_race(self):
        child = Mock()
        child.poll.side_effect = [None, 0, 0]
        child.terminate.side_effect = ProcessLookupError()
        restore.stop_children([child])
        child.kill.assert_not_called()


    def test_http_client_denies_arbitrary_routes_and_proxy_environment(self):
        client = restore.Client(32122)
        for path in ('http://example.com', '/api/ai/mcp/call', '/api/ai/work/runs/evil', '/../secrets.json'):
            with self.assertRaises(restore.RehearsalFailure):
                client.request(path)
        self.assertIsNone(restore.NoRedirect().redirect_request(None, None, None, None, None, None))

    def test_real_private_gateway_registration_csrf_and_readonly_allowlist(self):
        backup, files, hashes, _ = restore.inventory(self.root, restore.BACKUP)
        restore.copy_backup(backup, files, hashes, self.target)
        users_before = restore.original_users(self.target / 'tmp/auth/accounts.sqlite')
        invite = restore.create_copy_invite(self.target)
        requests = []
        expected_token = []
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass
            def do_GET(self):
                requests.append((self.path, self.headers.get('X-Internal-Token') == expected_token[0]))
                if self.path == '/api/ai/capabilities':
                    body = {'rag':False, 'terminal':False, 'mcpTools':[]}
                elif self.path == '/api/ai/coding/capabilities':
                    body = {'available':False}
                elif self.path == '/api/ai/models':
                    body = {'profiles':[]}
                else:
                    body = []
                data = json.dumps(body).encode()
                self.send_response(200)
                self.send_header('Content-Type','application/json')
                self.send_header('Content-Length',str(len(data)))
                self.end_headers()
                self.wfile.write(data)
        backend = ThreadingHTTPServer(('127.0.0.1',0), Handler)
        worker = threading.Thread(target=backend.serve_forever, daemon=True)
        worker.start()
        reservation = restore.reserve_port()
        port = reservation.getsockname()[1]
        env = restore.safe_environment(self.target, backend.server_port, port)
        # Windows Winsock needs SystemRoot; Linux production code remains a clean environment.
        if os.name == 'nt':
            env['SystemRoot'] = os.environ['SystemRoot']
        expected_token.append(env['INTERNAL_API_TOKEN'])
        reservation.close()
        child = subprocess.Popen([sys.executable, str(self.target / 'deploy/turing/rehearsal_gateway.py')],
            cwd=self.target, env=env, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        try:
            client = restore.Client(port)
            for _ in range(50):
                if child.poll() is not None:
                    error = child.stderr.read().decode('utf-8', errors='replace')
                    self.fail(error[-1600:])
                try:
                    if client.request('/api/auth/me')[0] == 200:
                        break
                except OSError:
                    pass
                time.sleep(.1)
            else:
                self.fail('Private gateway did not become ready')
            created = restore.verify_session(client, invite, [child], time.monotonic()+20)
            self.assertEqual([row for row in restore.original_users(self.target / 'tmp/auth/accounts.sqlite') if row[0] != created], users_before)
            self.assertEqual(requests, [(path, True) for path in restore.READ_PATHS])
            self.assertEqual(restore.original_users(self.backup / 'tmp/auth/accounts.sqlite'), users_before)
        finally:
            restore.stop_children([child])
            child.stderr.close()
            backend.shutdown(); backend.server_close(); worker.join()


if __name__ == '__main__':
    unittest.main()
