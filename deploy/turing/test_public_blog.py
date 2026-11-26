import os
import unittest
from unittest.mock import patch

from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer

import proxy


class AnonymousAccounts:
    def session(self, cookie):
        return None


class PublicBlogGatewayTest(unittest.IsolatedAsyncioTestCase):
    async def test_only_public_read_reaches_upstream_and_carries_no_identity(self):
        article_id = '36ad7e46-17e8-413b-9c20-35357c7ab0ea'
        path = '/api/open/blog/' + article_id
        calls = []

        async def upstream(request):
            calls.append((request.method, request.raw_path, request.headers))
            response = web.json_response({'id': article_id, 'markdown': 'published'})
            response.enable_compression()
            return response

        backend = web.Application()
        backend.router.add_route('*', '/{path:.*}', upstream)
        async with TestServer(backend) as server:
            with patch.dict(os.environ, {'SERVER_PORT': str(server.port)}):
                async with TestClient(TestServer(proxy.create_app(AnonymousAccounts()))) as client:
                    headers = {'Cookie': 'untrusted=1', 'Authorization': 'Bearer untrusted',
                               'X-User-Role': 'admin', 'X-Internal-Token': 'untrusted'}
                    response = await client.get(path + '?draft=true', headers=headers)
                    self.assertEqual(response.status, 200)
                    self.assertEqual((await response.json())['markdown'], 'published')
                    self.assertEqual(response.headers['Cache-Control'], 'no-store')
                    self.assertEqual((await client.get('/api/ai/blog/posts')).status, 401)
                    self.assertEqual((await client.get(path + '/edit')).status, 401)
                    self.assertIn((await client.post(path)).status, (401, 403, 405))
        self.assertEqual(len(calls), 1)
        method, raw_path, forwarded = calls[0]
        self.assertEqual((method, raw_path), ('GET', path))
        for name in headers:
            self.assertNotIn(name, forwarded)
