import os,tempfile,unittest
from pathlib import Path
from aiohttp import web
from aiohttp.test_utils import TestClient,TestServer
from accounts import Accounts,COOKIE
import proxy
class ProxyHttpTest(unittest.IsolatedAsyncioTestCase):
 async def asyncSetUp(self):
  self.temp=tempfile.TemporaryDirectory();self.old={k:os.environ.get(k) for k in ('INTERNAL_API_TOKEN','SERVER_PORT')}
  os.environ['INTERNAL_API_TOKEN']='server-secret'
  async def echo(request):return web.json_response({'origin':request.headers.get('Origin'),'token':request.headers.get('X-Internal-Token'),'auth':request.headers.get('Authorization'),'user':request.headers.get('X-User-Id'),'role':request.headers.get('X-User-Role'),'body':await request.text()})
  backend=web.Application();backend.router.add_route('*','/api/test',echo);self.backend=TestServer(backend);await self.backend.start_server();os.environ['SERVER_PORT']=str(self.backend.port)
  self.accounts=Accounts(Path(self.temp.name)/'auth.sqlite','yangbin','original-password-123')
  self.client=TestClient(TestServer(proxy.create_app(self.accounts)));await self.client.start_server()
  self.origin=str(self.client.make_url('/')).rstrip('/')
  response=await self.client.post('/api/auth/login',json={'username':'yangbin','password':'original-password-123'},headers={'Origin':self.origin})
  self.user=(await response.json())['user'];self.headers={'Origin':self.origin,'X-CSRF-Token':self.user['csrf']}
 async def asyncTearDown(self):
  await self.client.close();await self.backend.close();self.temp.cleanup()
  for k,v in self.old.items():
   if v is None:os.environ.pop(k,None)
   else:os.environ[k]=v
 async def test_same_origin_post_strips_untrusted_headers(self):
  response=await self.client.post('/api/test',data='payload',headers={**self.headers,'X-User-Id':'fake','X-User-Role':'fake','X-Internal-Token':'fake','Authorization':'Basic fake'})
  self.assertEqual(response.status,200);self.assertEqual(await response.json(),{'origin':None,'token':'server-secret','auth':None,'user':'admin','role':'admin','body':'payload'})
 async def test_cross_origin_and_missing_csrf_are_blocked(self):
  for headers in ({**self.headers,'Origin':'https://untrusted.example'},{**self.headers,'Sec-Fetch-Site':'cross-site'},{'Origin':self.origin},{'X-CSRF-Token':self.user['csrf']}):
   response=await self.client.post('/api/test',headers=headers);self.assertEqual(response.status,403)
 async def test_unauthenticated_basic_header_does_not_bypass_sessions(self):
  self.client.session.cookie_jar.clear();response=await self.client.get('/api/test',headers={'Authorization':'Basic fake','X-User-Id':'admin','X-User-Role':'admin'});self.assertEqual(response.status,401)
  response=await self.client.get('/api/auth/me');self.assertIsNone((await response.json())['user'])
 async def test_cookie_is_http_only_and_logout_revokes_it(self):
  response=await self.client.post('/api/auth/login',json={'username':'yangbin','password':'original-password-123'},headers={'Origin':self.origin})
  self.assertTrue(response.cookies[COOKIE]['httponly']);self.assertEqual(response.cookies[COOKIE]['samesite'],'Strict')
  user=(await response.json())['user'];response=await self.client.post('/api/auth/logout',headers={'Origin':self.origin,'X-CSRF-Token':user['csrf']});self.assertEqual(response.status,200)
  self.assertEqual((await self.client.get('/api/test')).status,401)
 async def test_registered_users_cannot_manage_accounts_or_spoof_role(self):
  invite=self.accounts.create_invite(1,7)
  response=await self.client.post('/api/auth/register',json={'username':'alice','password':'secure-password-123','invite':invite['code'],'role':'admin'},headers={'Origin':self.origin})
  user=(await response.json())['user'];self.assertEqual(user['role'],'user')
  self.assertEqual((await self.client.get('/api/auth/users')).status,403)
  self.assertEqual((await self.client.post('/api/auth/invites',json={},headers={'Origin':self.origin,'X-CSRF-Token':user['csrf']})).status,403)
  response=await self.client.get('/api/test',headers={'X-User-Id':'admin','X-User-Role':'admin'});data=await response.json();self.assertEqual(data['user'],user['id']);self.assertEqual(data['role'],'user')
 async def test_admin_invite_lifecycle_and_invalid_registration(self):
  response=await self.client.post('/api/auth/invites',json={'maxUses':1,'days':7},headers=self.headers);invite=await response.json();self.assertIn('code',invite)
  response=await self.client.delete('/api/auth/invites/'+invite['id'],headers=self.headers);self.assertEqual(response.status,200)
  response=await self.client.post('/api/auth/register',json={'username':'alice','password':'secure-password-123','invite':invite['code']},headers={'Origin':self.origin});self.assertEqual(response.status,400)
 async def test_login_attempts_are_rate_limited(self):
  for _ in range(10):response=await self.client.post('/api/auth/login',json={'username':'nobody','password':'bad-password'},headers={'Origin':self.origin})
  response=await self.client.post('/api/auth/login',json={'username':'nobody','password':'bad-password'},headers={'Origin':self.origin});self.assertEqual(response.status,429)
if __name__=='__main__':unittest.main()
