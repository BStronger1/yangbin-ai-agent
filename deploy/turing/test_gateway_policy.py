"""Real loopback HTTP contracts; no model or paid service involved."""
import asyncio
import json
import logging
import os
import unittest
from types import SimpleNamespace
from unittest.mock import patch
from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer
import proxy
from gateway_policy import Admission, expensive, transport_settings


class AdmissionTest(unittest.TestCase):
    def test_limits_release_and_separate_users(self):
        with patch.dict(os.environ, {'GATEWAY_MODEL_CONCURRENT':'2','GATEWAY_USER_MODEL_CONCURRENT':'1'}):
            gate=Admission()
        self.assertIsNone(gate.acquire('a',True))
        self.assertEqual(gate.acquire('a',True),'capacity')
        self.assertIsNone(gate.acquire('b',True))
        self.assertEqual(gate.acquire('c',True),'capacity')
        gate.release('a',True)
        self.assertIsNone(gate.acquire('c',True))
        gate.release('b',True);gate.release('c',True)
        self.assertEqual(gate.total,0);self.assertEqual(gate.models,0);self.assertFalse(gate.active)

    def test_rate_cap_independent_from_concurrent_release(self):
        with patch.dict(os.environ, {'GATEWAY_USER_MODEL_RPM':'1'}):gate=Admission()
        self.assertIsNone(gate.acquire('a',True));gate.release('a',True)
        self.assertEqual(gate.acquire('a',True),'rate')
        self.assertIsNone(gate.acquire('a',False));gate.release('a',False)
        self.assertIsNone(gate.acquire('b',True));gate.release('b',True)

    def test_real_model_routes_and_cancel_classification(self):
        for method,path in [('GET','/api/ai/manus/chat'),('GET','/api/ai/love_app/chat/sse'),
                ('POST','/api/ai/work/partners/office/jobs'),('POST','/api/ai/work/partners/office/messages'),
                ('POST','/api/ai/coding/sessions/123/turns'),('POST','/api/ai/tasks/123/start'),
                ('POST','/api/ai/mcp/call'),('POST','/api/ai/plans/123/repair'),('POST','/ppt/api/v1/projects')]:
            self.assertTrue(expensive(SimpleNamespace(method=method,path=path)),path)
        for path in ['/api/ai/coding/sessions/123/cancel','/api/ai/work/partners/office/conversations/123/stop']:
            self.assertFalse(expensive(SimpleNamespace(method='POST',path=path)))

    def test_production_transport_fails_closed(self):
        for settings in [{'GATEWAY_TLS_MODE':'off'}, {'GATEWAY_TLS_MODE':'reverse-proxy','PUBLIC_BIND':'0.0.0.0'},
                {'GATEWAY_TLS_MODE':'direct','GATEWAY_TLS_CERT':'','GATEWAY_TLS_KEY':''}]:
            with patch.dict(os.environ,{'AGENT_PRODUCTION_MODE':'true','SESSION_COOKIE_SECURE':'true',**settings}),self.assertRaises(ValueError):
                transport_settings('https://agent.example')
        with patch.dict(os.environ,{'AGENT_PRODUCTION_MODE':'true','GATEWAY_TLS_MODE':'reverse-proxy','PUBLIC_BIND':'127.0.0.1','SESSION_COOKIE_SECURE':'true'}):
            config,context=transport_settings('https://agent.example')
            self.assertTrue(config['production']);self.assertIsNone(context)
            with self.assertRaises(ValueError):transport_settings('http://agent.example')

    def test_invalid_zero_quota_is_not_unlimited(self):
        with patch.dict(os.environ,{'AGENT_PRODUCTION_MODE':'TRUE'}),self.assertRaises(ValueError):transport_settings('https://agent.example')
        with patch.dict(os.environ,{'GATEWAY_MAX_CONCURRENT':'0'}),self.assertRaises(ValueError):Admission()


class GatewayHttpTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.owner={'id':'admin','role':'admin','csrf':'test-csrf'}
        self.entered=asyncio.Event();self.release=asyncio.Event();self.seen=[]
        async def backend(request):
            self.seen.append(dict(request.headers))
            if request.path.endswith('/slow'):
                self.entered.set();await self.release.wait()
            if request.path.endswith('/broken'):raise RuntimeError('never-log-private-prompt')
            return web.json_response({'ok':True},headers={'X-Request-Id':'forged-upstream'})
        app=web.Application();app.router.add_route('*','/{path:.*}',backend)
        self.backend=TestServer(app);await self.backend.start_server()
        self.env=patch.dict(os.environ,{'SERVER_PORT':str(self.backend.port),'GATEWAY_USER_CONCURRENT':'1','GATEWAY_USER_MODEL_RPM':'1'});self.env.start()
        accounts=SimpleNamespace(session=lambda token:self.owner)
        self.app=proxy.create_app(accounts)
        self.client=TestClient(TestServer(self.app));await self.client.start_server()
        self.headers={'Origin':str(self.client.make_url('/')).rstrip('/'),'X-CSRF-Token':'test-csrf'}
    async def asyncTearDown(self):
        self.release.set();await self.client.close();await self.backend.close();self.env.stop()

    async def test_password_hash_slots_reject_without_waiting(self):
        slots=self.app['password_slots']
        await slots.acquire();await slots.acquire()
        try:
            response=await self.client.post('/api/auth/login',json={'username':'alice','password':'private'},headers={'Origin':self.headers['Origin']})
            self.assertEqual(response.status,503);self.assertEqual(response.headers['Retry-After'],'2')
            self.assertNotIn('private',await response.text())
        finally:slots.release();slots.release()

    async def test_trace_is_new_and_identity_headers_never_trusted(self):
        response=await self.client.get('/api/test?prompt=private-prompt',headers={'X-Request-Id':'external-id','traceparent':'forged','baggage':'private','X-User-Id':'forged','X-Forwarded-For':'private-ip'})
        trace=response.headers['X-Request-Id'];self.assertRegex(trace,r'^[a-f0-9]{32}$')
        seen={k.lower():v for k,v in self.seen[-1].items()}
        self.assertEqual(seen['x-request-id'],trace);self.assertEqual(seen['x-user-id'],'admin')
        for name in ['traceparent','baggage','x-forwarded-for']:self.assertNotIn(name,seen)
        second=await self.client.get('/api/test');self.assertNotEqual(trace,second.headers['X-Request-Id'])

    async def test_concurrency_rejects_and_recovers_without_queue(self):
        pending=asyncio.create_task(self.client.get('/api/slow'));await self.entered.wait()
        busy=await self.client.get('/api/test');self.assertEqual(busy.status,503);self.assertEqual(busy.headers['Retry-After'],'2')
        self.release.set();self.assertEqual((await pending).status,200)
        self.assertEqual((await self.client.get('/api/test')).status,200)
        self.assertEqual(self.app['admission'].total,0)

    async def test_model_rpm_does_not_block_cancellation(self):
        response=await self.client.post('/api/ai/work/partners/office/jobs',json={},headers=self.headers);self.assertEqual(response.status,200)
        response=await self.client.post('/api/ai/work/partners/office/jobs',json={},headers=self.headers);self.assertEqual(response.status,429)
        self.assertEqual(response.headers['Retry-After'],'60')
        self.assertEqual((await self.client.post('/api/ai/coding/sessions/123/cancel',json={},headers=self.headers)).status,200)

    async def test_metrics_and_logs_do_not_include_content_or_identifiers(self):
        with self.assertLogs('yangbin.gateway',logging.INFO) as captured:
            response=await self.client.post('/api/private-secret-path?secret=private-query',data='private-body',headers=self.headers)
            await response.read()
        data=await (await self.client.get('/api/ops/metrics')).json()
        serialized=json.dumps(data)+str(captured.output)
        for secret in ['private-secret-path','private-query','private-body','admin','test-csrf']:self.assertNotIn(secret,serialized)
        self.assertIn('http_complete',serialized);self.assertIn('requestId',serialized)
        self.assertEqual(data['scope'],'single-gateway-process')

    async def test_admin_only_metrics_and_auth_trace(self):
        health=await (await self.client.get('/api/ops/health')).json();self.assertEqual(health['gateway'],'ready')
        self.owner={'id':'a'*32,'role':'user','csrf':'test-csrf'}
        self.assertEqual((await self.client.get('/api/ops/metrics')).status,403)
        self.owner=None
        response=await self.client.get('/api/ops/health');self.assertEqual(response.status,401);self.assertIn('X-Request-Id',response.headers)

    async def test_unexpected_handler_error_does_not_leak_exception(self):
        async def failure(request):raise RuntimeError('private-upstream-secret')
        # Use the middleware directly with a real request-independent fixture.
        from gateway_policy import trace
        class Request(dict):
            app=self.app
            path='/api/test'
        response=await trace(Request(),failure)
        self.assertEqual(response.status,500);self.assertNotIn('private-upstream-secret',response.text)
        self.assertEqual(self.app['admission'].total,0)


if __name__=='__main__':unittest.main()
