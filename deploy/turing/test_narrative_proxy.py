"""Real HTTP gateway contract; upstream fixture never invokes a model."""
import os
import tempfile
import unittest
from pathlib import Path
from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer
from accounts import Accounts
import proxy


class NarrativeProxyTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.old = {key: os.environ.get(key) for key in ("NARRATIVE_PORT", "AGENT_BRIDGE_SECRET", "SERVER_PORT")}
        self.secret = "test-bridge-" + "x" * 40
        os.environ["AGENT_BRIDGE_SECRET"] = self.secret
        self.seen = []
        self.allowed = True
        self.policy_seen = []
        async def upstream(request):
            if request.path == "/api/ai/work/partners/presentation/access":
                self.policy_seen.append(dict(request.headers))
                return web.json_response({"mode":"RESTRICTED", "enabled":self.allowed})
            self.seen.append(dict(request.headers))
            if request.path == "/api/v1/health":
                return web.json_response({"database": "ok", "redis": "ok"})
            if request.path == "/api/v1/events":
                response = web.StreamResponse(headers={"Content-Type": "text/event-stream"})
                await response.prepare(request)
                await response.write(b'data: {"done":true}\\n\\n')
                return response
            return web.json_response({"path": request.path, "query": request.query_string,
                "owner": request.headers.get("X-Agent-Owner"),
                "secretMatches": request.headers.get("X-Agent-Bridge-Token") == self.secret,
                "cookie": request.headers.get("Cookie"), "auth": request.headers.get("Authorization"),
                "length": len(await request.read())})
        backend = web.Application(client_max_size=12*1024*1024)
        backend.router.add_route("*", "/{path:.*}", upstream)
        self.backend = TestServer(backend); await self.backend.start_server()
        os.environ["NARRATIVE_PORT"] = str(self.backend.port)
        os.environ["SERVER_PORT"] = str(self.backend.port)
        self.accounts = Accounts(Path(self.tmp.name)/"auth.sqlite", "yangbin", "test-password-12345")
        self.client = TestClient(TestServer(proxy.create_app(self.accounts))); await self.client.start_server()
        self.origin = str(self.client.make_url("/")).rstrip("/")
        login = await self.client.post("/api/auth/login", json={"username":"yangbin","password":"test-password-12345"}, headers={"Origin":self.origin})
        self.user = (await login.json())["user"]
        self.headers = {"Origin":self.origin,"X-CSRF-Token":self.user["csrf"]}

    async def asyncTearDown(self):
        await self.client.close(); await self.backend.close(); self.tmp.cleanup()
        for key,value in self.old.items():
            if value is None: os.environ.pop(key,None)
            else: os.environ[key] = value

    async def test_partner_restriction_blocks_direct_api_and_assets(self):
        self.allowed = False
        for path in ("/ppt/", "/ppt/assets/main.js", "/ppt/api/v1/projects"):
            self.assertEqual((await self.client.get(path)).status,403)
        self.assertEqual((await self.client.post("/ppt/api/v1/projects",json={},headers=self.headers)).status,403)
        self.assertEqual(len(self.seen),0)
        self.assertTrue(all(h["X-User-Id"] == "admin" for h in self.policy_seen))
        self.allowed = True
        self.assertEqual((await self.client.get("/ppt/api/v1/projects")).status,200)

    async def test_policy_service_failure_is_fail_closed(self):
        os.environ["SERVER_PORT"] = "1"
        self.assertEqual((await self.client.get("/ppt/api/v1/projects")).status,503)
        self.assertEqual(len(self.seen),0)

    async def test_identity_cannot_be_spoofed_and_subpath_is_preserved(self):
        response = await self.client.post("/ppt/api/v1/projects?mode=draft", data=b"source", headers={
            **self.headers,"X-Agent-Owner":"forged","X-Agent-Bridge-Token":"forged","Authorization":"Bearer fake"})
        self.assertEqual(response.status,200)
        self.assertEqual(await response.json(),{"path":"/api/v1/projects","query":"mode=draft",
            "owner":"admin","secretMatches":True,"cookie":None,"auth":None,"length":6})

    async def test_session_and_csrf_required_for_embedded_routes(self):
        self.assertEqual((await self.client.post("/ppt/api/v1/projects")).status,403)
        self.assertEqual((await self.client.get("/ppt/",headers={"Origin":"https://evil.example"})).status,403)
        self.client.session.cookie_jar.clear()
        for path in ("/ppt/","/ppt/assets/main.js","/ppt/api/v1/projects","/api/ppt/status"):
            self.assertEqual((await self.client.get(path)).status,401)
        self.assertEqual(len(self.seen),0)

    async def test_large_document_and_sse_survive_proxy(self):
        response=await self.client.post("/ppt/api/v1/upload",data=b"x"*(3*1024*1024),headers=self.headers)
        self.assertEqual(response.status,200);self.assertEqual((await response.json())["length"],3*1024*1024)
        response=await self.client.get("/ppt/api/v1/events")
        self.assertEqual(response.headers["Content-Type"],"text/event-stream")
        self.assertIn('data: {"done":true}',await response.text())
        self.assertEqual(response.headers["Cache-Control"],"no-store")

    async def test_status_and_disabled_configuration(self):
        self.assertEqual(await (await self.client.get("/api/ppt/status")).json(),{"enabled":True,"ready":True})
        os.environ.pop("NARRATIVE_PORT")
        self.assertEqual(await (await self.client.get("/api/ppt/status")).json(),{"enabled":False,"ready":False})
        self.assertEqual((await self.client.get("/ppt/")).status,503)

    async def test_new_account_receives_distinct_identity_and_logout_revokes_access(self):
        invite=self.accounts.create_invite(1,1)
        response=await self.client.post("/api/auth/register",json={"username":"alice","password":"test-password-alice","invite":invite["code"]},headers={"Origin":self.origin})
        user=(await response.json())["user"]
        data=await (await self.client.get("/ppt/api/v1/projects",headers={"X-Agent-Owner":"admin"})).json()
        self.assertEqual(data["owner"],user["id"]);self.assertNotEqual(data["owner"],"admin")
        await self.client.post("/api/auth/logout",headers={"Origin":self.origin,"X-CSRF-Token":user["csrf"]})
        self.assertEqual((await self.client.get("/ppt/api/v1/projects")).status,401)
