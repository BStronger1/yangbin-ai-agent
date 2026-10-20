import concurrent.futures
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from accounts import Accounts
from public_gateway import public_environment, start, stop


class PublicGatewayConfigTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / 'tmp/auth').mkdir(parents=True)
        (self.root / 'tmp/auth/accounts.sqlite').touch()
        (self.root / 'secrets.json').write_text(json.dumps({'env': {
            'INTERNAL_API_TOKEN': 'test-private-value', 'PUBLIC_BIND': '172.16.1.2',
            'PUBLIC_PORT': '18825', 'SESSION_COOKIE_SECURE': 'false'}}))

    def config(self, origin='https://www.yangbinai.homes', port=18826):
        (self.root / 'public-gateway.json').write_text(json.dumps({'origin': origin, 'port': port}))

    def test_enforces_transport_and_preserves_backend_secret(self):
        self.config()
        with patch.dict(os.environ, {}, clear=True):
            env = public_environment(self.root)
        self.assertEqual(env['PUBLIC_BIND'], '127.0.0.1')
        self.assertEqual(env['PUBLIC_PORT'], '18826')
        self.assertEqual(env['SESSION_COOKIE_SECURE'], 'true')
        self.assertEqual(env['AGENT_PRODUCTION_MODE'], 'true')
        self.assertEqual(env['GATEWAY_TLS_MODE'], 'reverse-proxy')
        self.assertEqual(env['INTERNAL_API_TOKEN'], 'test-private-value')

    def test_rejects_unsafe_origins_and_other_service_port(self):
        for origin in ('http://www.yangbinai.homes', 'https://a:b@site.com',
                       'https://site.com/path', 'https://site.com?token=secret',
                       'https://site.com:18825', ''):
            self.config(origin)
            with self.assertRaises(ValueError):
                public_environment(self.root)
        self.config(port=18825)
        with self.assertRaises(ValueError):
            public_environment(self.root)

    def test_requires_preexisting_account_database(self):
        self.config()
        (self.root / 'tmp/auth/accounts.sqlite').unlink()
        with self.assertRaises(ValueError):
            public_environment(self.root)


class PublicGatewayLifecycleTest(unittest.TestCase):
    def test_start_retries_connection_refusal_without_secret_arguments(self):
        with patch('public_gateway.running', side_effect=[False, True, True]), \
             patch('public_gateway.public_environment', return_value={'SECRET': 'private-value'}), \
             patch('public_gateway.subprocess.run') as command, \
             patch('readiness.gateway_ready', side_effect=[ConnectionRefusedError(), True]), \
             patch('public_gateway.time.sleep'):
            start(Path('/app'))
        self.assertNotIn('private-value', str(command.call_args))
        self.assertIn('yangbin-ai-agent-public', str(command.call_args))

    def test_stop_targets_only_public_session(self):
        with patch('public_gateway.running', side_effect=[True, True, False]), \
             patch('public_gateway.subprocess.run') as command, \
             patch('public_gateway.time.sleep'):
            stop()
        self.assertEqual(command.call_args.args[0],
                         ['tmux', 'send-keys', '-t', 'yangbin-ai-agent-public:0.0', 'C-c'])


class SharedAccountsTest(unittest.TestCase):
    def test_two_stores_share_revocation_and_atomic_invites(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'accounts.sqlite'
            first = Accounts(path, 'admin', 'original-password-123')
            second = Accounts(path, 'admin', 'unused-password-456')
            token, _ = second.login('admin', 'original-password-123')
            self.assertIsNotNone(first.session(token))
            first.logout(token)
            self.assertIsNone(second.session(token))
            invite = first.create_invite(1, 1)

            def register(args):
                store, name = args
                try:
                    return store.register(name, 'valid-password-123', invite['code'])
                except ValueError:
                    return None
            with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
                results = list(pool.map(register, [(first, 'alice'), (second, 'bob')]))
            winners = [value for value in results if value]
            self.assertEqual(len(winners), 1)
            token, user = winners[0]
            self.assertIsNotNone(first.session(token))
            self.assertIsNotNone(second.session(token))
            first.set_active(user['id'], False)
            self.assertIsNone(second.session(token))


if __name__ == '__main__':
    unittest.main()
