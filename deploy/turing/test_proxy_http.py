import base64,os,unittest
from aiohttp import web
from aiohttp.test_utils import TestClient,TestServer
import proxy
class ProxyHttpTest(unittest.IsolatedAsyncioTestCase):
 async def asyncSetUp(self):
  self.old={k:os.environ.get(k) for k in ("ACCESS_USER","ACCESS_PASSWORD","INTERNAL_API_TOKEN","SERVER_PORT")}
  os.environ.update(ACCESS_USER="test-user",ACCESS_PASSWORD="test-password",INTERNAL_API_TOKEN="server-secret")
  async def echo(request):return web.json_response({"origin":request.headers.get("Origin"),"token":request.headers.get("X-Internal-Token"),"auth":request.headers.get("Authorization"),"body":await request.text()})
  backend=web.Application();backend.router.add_route("*","/api/test",echo)
  self.backend=TestServer(backend);await self.backend.start_server();os.environ["SERVER_PORT"]=str(self.backend.port)
  app=web.Application(middlewares=[proxy.authenticate]);app.cleanup_ctx.append(proxy.clients);app.router.add_route("*","/api/{path:.*}",proxy.api)
  self.client=TestClient(TestServer(app));await self.client.start_server()
  self.auth="Basic "+base64.b64encode(b"test-user:test-password").decode()
 async def asyncTearDown(self):
  await self.client.close();await self.backend.close()
  for k,v in self.old.items():
   if v is None:os.environ.pop(k,None)
   else:os.environ[k]=v
 async def test_same_origin_post_reaches_backend_without_false_cors(self):
  response=await self.client.post("/api/test",data="payload",headers={"Authorization":self.auth,"Origin":str(self.client.make_url("/")).rstrip("/"),"Sec-Fetch-Site":"same-origin","X-Internal-Token":"spoofed"})
  self.assertEqual(response.status,200);data=await response.json();self.assertEqual(data,{"origin":None,"token":"server-secret","auth":None,"body":"payload"})
 async def test_cross_origin_post_stays_blocked(self):
  response=await self.client.post("/api/test",headers={"Authorization":self.auth,"Origin":"https://untrusted.example"});self.assertEqual(response.status,403)
  response=await self.client.post("/api/test",headers={"Authorization":self.auth,"Sec-Fetch-Site":"cross-site"});self.assertEqual(response.status,403)
 async def test_unauthenticated_request_stays_blocked(self):
  response=await self.client.post("/api/test");self.assertEqual(response.status,401)
if __name__=="__main__":unittest.main()
