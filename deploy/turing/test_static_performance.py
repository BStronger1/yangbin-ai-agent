"""Real HTTP cache/compression contracts; private routes must stay uncached."""
import gzip
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from aiohttp.test_utils import TestClient, TestServer
from accounts import Accounts
import proxy


class StaticPerformanceTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        root = Path(self.tmp.name)
        (root/'assets').mkdir()
        self.body = b'console.log("public build asset");' * 100
        (root/'assets/index-Abcd1234.js').write_bytes(self.body)
        (root/'assets/index-Abcd1234.js.gz').write_bytes(gzip.compress(self.body))
        (root/'assets/plain.js').write_bytes(self.body)
        (root/'index.html').write_text('<html>shell</html>')
        self.env = patch.dict(os.environ, {'AGENT_FRONTEND_DIR':str(root)})
        self.env.start()
        self.client = TestClient(TestServer(proxy.create_app(Accounts(root/'auth.sqlite','tester','test-password-12345'))))
        await self.client.start_server()

    async def asyncTearDown(self):
        await self.client.close()
        self.env.stop()
        self.tmp.cleanup()

    async def test_versioned_asset_cache_and_gzip_negotiation(self):
        r = await self.client.get('/assets/index-Abcd1234.js', headers={'Accept-Encoding':'gzip'})
        self.assertEqual(r.status,200)
        self.assertEqual(await r.read(),self.body)
        self.assertEqual(r.headers['Content-Encoding'],'gzip')
        self.assertIn('Accept-Encoding',r.headers['Vary'])
        self.assertIn('immutable',r.headers['Cache-Control'])
        plain = await self.client.get('/assets/index-Abcd1234.js', headers={'Accept-Encoding':'identity'})
        self.assertNotIn('Content-Encoding',plain.headers)
        self.assertEqual(await plain.read(),self.body)

    async def test_html_and_unversioned_files_are_not_cached(self):
        for path in ('/','/coding','/assets/plain.js'):
            r = await self.client.get(path)
            self.assertEqual(r.status,200)
            self.assertEqual(r.headers['Cache-Control'],'no-store')

    async def test_private_and_missing_routes_do_not_receive_asset_policy(self):
        for path, status in (('/api/auth/me',200),('/api/ai/models',401),('/ppt/assets/index-Abcd1234.js',401),('/assets/missing-Abcd1234.js',404)):
            r = await self.client.get(path)
            self.assertEqual(r.status,status)
            self.assertNotIn('public',r.headers.get('Cache-Control',''))
            self.assertNotIn('immutable',r.headers.get('Cache-Control',''))


if __name__ == '__main__': unittest.main()
