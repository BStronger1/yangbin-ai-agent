from contextlib import closing
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
from unittest.mock import patch
import ops_probe


class OperationsProbeTest(unittest.TestCase):
    def response(self, payload, status=200):
        factory = patch('ops_probe.http.client.HTTPConnection')
        connection = factory.start().return_value
        self.addCleanup(factory.stop)
        connection.getresponse.return_value.status = status
        connection.getresponse.return_value.read.return_value = payload
        return connection

    def test_probe_backend_and_no_redirect(self):
        connection = self.response(b'ok')
        self.assertTrue(ops_probe.probe('backend')['healthy'])
        connection.request.assert_called_with('GET', '/api/health')
        connection.getresponse.return_value.status = 302
        self.assertFalse(ops_probe.probe('backend')['healthy'])
        connection.close.assert_called()

    def test_gateway_requires_anonymous_json(self):
        connection = self.response(b'{"user":null}')
        self.assertTrue(ops_probe.probe('gateway')['healthy'])
        for payload in (b'{}', b'[]', b'{"user":{"id":"secret"}}', b'x' * 65537):
            connection.getresponse.return_value.read.return_value = payload
            self.assertFalse(ops_probe.probe('gateway')['healthy'])


    def test_configured_lan_gateway_routes_to_local_bind(self):
        (self.root / 'secrets.json').write_text(json.dumps({'env': {
            'PUBLIC_BIND': '172.16.185.103', 'PUBLIC_PORT': '18825', 'MODEL_API_KEY': 'private-marker'}}))
        with patch('ops_probe.socket.socket') as factory:
            address = ops_probe.configured_gateway(self.root)
            factory.return_value.__enter__.return_value.bind.assert_called_once_with(('172.16.185.103', 0))
        self.assertEqual(address, ('172.16.185.103', 18825))
        with patch('ops_probe.http.client.HTTPConnection') as connection:
            connection.return_value.getresponse.return_value.status = 200
            connection.return_value.getresponse.return_value.read.return_value = b'{"user":null}'
            report = ops_probe.probe('gateway', gateway_address=address)
            connection.assert_called_once_with('172.16.185.103', 18825, timeout=2)
        self.assertTrue(report['healthy'])
        self.assertNotIn('private-marker', json.dumps(report))

    def test_configured_gateway_rejects_nonlocal_address_before_request(self):
        (self.root / 'secrets.json').write_text(json.dumps({'env': {
            'PUBLIC_BIND': '203.0.113.5', 'PUBLIC_PORT': '18825'}}))
        with patch('ops_probe.socket.socket') as factory:
            factory.return_value.__enter__.return_value.bind.side_effect = OSError('not this host')
            with self.assertRaises(OSError):
                ops_probe.configured_gateway(self.root)
            with patch('ops_probe.probe', return_value={'healthy': True}) as probe:
                report = ops_probe.health(self.root)
                self.assertFalse(report['healthy'])
                self.assertEqual([call.args[0] for call in probe.call_args_list], ['backend', 'publicGateway', 'tunnel'])

    def test_configured_wildcard_uses_loopback_and_missing_config_fails_closed(self):
        (self.root / 'secrets.json').write_text(json.dumps({'env': {'PUBLIC_BIND': '0.0.0.0'}}))
        with patch('ops_probe.socket.socket'):
            self.assertEqual(ops_probe.configured_gateway(self.root), ('127.0.0.1', 18825))
        (self.root / 'secrets.json').unlink()
        with patch('ops_probe.probe', return_value={'healthy': True}) as probe:
            self.assertFalse(ops_probe.health(self.root)['healthy'])
            self.assertEqual(probe.call_count, 3)


    def test_tunnel_requires_nonzero_integer_connections(self):
        connection = self.response(b'{"readyConnections":4}')
        self.assertTrue(ops_probe.probe('tunnel')['healthy'])
        for payload in (b'{"readyConnections":0}', b'{"readyConnections":true}', b'{}'):
            connection.getresponse.return_value.read.return_value = payload
            self.assertFalse(ops_probe.probe('tunnel')['healthy'])

    def test_errors_do_not_leak_response_or_exception(self):
        connection = self.response(b'private-body')
        connection.request.side_effect = RuntimeError('credential-must-not-leak')
        value = ops_probe.probe('backend')
        self.assertFalse(value['healthy'])
        self.assertNotIn('credential', json.dumps(value))
        self.assertNotIn('private', json.dumps(value))

    def test_alert_debounce_dedup_recovery(self):
        report = {'checks': [{'component': 'backend', 'healthy': False}]}
        state = {}
        for attempt in range(3):
            state, events = ops_probe.alert_transition(state, report)
            self.assertEqual(len(events), 1 if attempt == 2 else 0)
        state, events = ops_probe.alert_transition(state, report)
        self.assertEqual(events, [])
        report['checks'][0]['healthy'] = True
        state, events = ops_probe.alert_transition(state, report)
        self.assertEqual(events, [{'component': 'backend', 'event': 'resolved'}])
        _, events = ops_probe.alert_transition(state, report)
        self.assertEqual(events, [])


    def test_monitor_persists_debounces_and_cleans_lock(self):
        report = {'healthy': False, 'checks': [{'component': 'backend', 'healthy': False}]}
        with patch('ops_probe.health', return_value=report):
            self.assertEqual(ops_probe.monitor(self.root)['events'], [])
            self.assertEqual(ops_probe.monitor(self.root)['events'], [])
            self.assertEqual(ops_probe.monitor(self.root)['events'], [{'component': 'backend', 'event': 'firing'}])
            self.assertEqual(ops_probe.monitor(self.root)['events'], [])
        self.assertFalse((self.root / 'run/ops-health.lock').exists())
        self.assertEqual(len(list((self.root / 'run').iterdir())), 1)
        self.assertTrue((self.root / 'run/ops-health.json').is_file())

    def test_monitor_concurrent_lock_refuses_poll(self):
        (self.root / 'run').mkdir()
        (self.root / 'run/ops-health.lock').write_text('')
        with patch('ops_probe.health') as health:
            with self.assertRaises(FileExistsError):
                ops_probe.monitor(self.root)
            health.assert_not_called()


    def test_load_limits_and_percentiles(self):
        for requests, concurrency in ((61, 1), (0, 1), (4, 5), (4, 0), (True, 1)):
            with self.assertRaises(ValueError):
                ops_probe.bounded_load(requests, concurrency)
        with patch('ops_probe.probe', return_value={'healthy': True, 'durationMs': 12}) as probe:
            result = ops_probe.bounded_load(20, 2)
        self.assertEqual(probe.call_count, 20)
        self.assertEqual(result['p95Ms'], 12)
        self.assertEqual(result['errors'], 0)
        self.assertEqual(result['modelRequests'], 0)
        for call in probe.call_args_list:
            self.assertEqual(call.args, ('backend',))

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.backup = self.root / 'releases/before-workspace-test'
        (self.backup / 'tmp/auth').mkdir(parents=True)
        (self.backup / 'tmp/model-profiles').mkdir()
        with closing(sqlite3.connect(self.backup / 'tmp/auth/accounts.sqlite')) as db:
            db.executescript('CREATE TABLE users(id TEXT); CREATE TABLE sessions(id TEXT); CREATE TABLE invites(id TEXT);')
        (self.backup / 'tmp/model-profiles/master.key').write_bytes(b'x' * 32)
        (self.backup / 'secrets.json').write_text('{"secret":"private-marker"}', encoding='utf-8')
        (self.backup / 'data-sha256.json').write_text('{}', encoding='utf-8')

    def test_restore_private_copy_preserves_original_and_removes_copy(self):
        before = {str(p): ops_probe.digest(p) for p in self.backup.rglob('*') if p.is_file()}
        result = ops_probe.restore_check(self.root, self.backup.name)
        self.assertTrue(result['restored'])
        self.assertFalse(result['applicationBootVerified'])
        self.assertFalse(result['liveDataModified'])
        self.assertEqual(list((self.root / 'run').iterdir()), [])
        self.assertEqual(before, {str(p): ops_probe.digest(p) for p in self.backup.rglob('*') if p.is_file()})
        self.assertNotIn('private-marker', json.dumps(result))

    def test_restore_rejects_traversal(self):
        for name in ('../before-workspace-test', 'before-workspace-test/../../tmp', '', 'other'):
            with self.assertRaises(ValueError):
                ops_probe.restore_check(self.root, name)

    def test_restore_detects_manifest_corruption(self):
        (self.backup / 'data-sha256.json').write_text('{"secrets.json":"incorrect"}')
        with self.assertRaises(ValueError):
            ops_probe.restore_check(self.root, self.backup.name)
        self.assertFalse((self.root / 'run').exists())

    def test_restore_requires_original_key(self):
        (self.backup / 'tmp/model-profiles/master.key').unlink()
        with self.assertRaises(ValueError):
            ops_probe.restore_check(self.root, self.backup.name)

    def test_restore_detects_bad_sqlite_and_cleans_temporary_copy(self):
        (self.backup / 'tmp/auth/accounts.sqlite').write_bytes(b'invalid')
        with self.assertRaises(sqlite3.DatabaseError):
            ops_probe.restore_check(self.root, self.backup.name)
        self.assertEqual(list((self.root / 'run').iterdir()), [])

    def test_restore_detects_invalid_json(self):
        (self.backup / 'secrets.json').write_text('invalid')
        with self.assertRaises(json.JSONDecodeError):
            ops_probe.restore_check(self.root, self.backup.name)
        self.assertEqual(list((self.root / 'run').iterdir()), [])

    def test_copy_bound(self):
        with self.assertRaises(ValueError):
            ops_probe.backup_files(self.backup, max_bytes=1)


if __name__ == '__main__':
    unittest.main()
