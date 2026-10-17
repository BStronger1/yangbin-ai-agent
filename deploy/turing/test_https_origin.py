import os,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
from aiohttp.test_utils import TestClient,TestServer
from accounts import Accounts,COOKIE
import proxy
class HttpsOriginTest(unittest.IsolatedAsyncioTestCase):
 async def test_tls_terminator_uses_explicit_origin_and_secure_cookie(self):
  with tempfile.TemporaryDirectory() as directory,patch.dict(os.environ,{'PUBLIC_ORIGIN':'https://agent.example','SESSION_COOKIE_SECURE':'true'}):
   accounts=Accounts(Path(directory)/'auth.sqlite','admin','test-password-only')
   async with TestClient(TestServer(proxy.create_app(accounts))) as client:
    response=await client.post('/api/auth/login',json={'username':'admin','password':'test-password-only'},headers={'Origin':'https://agent.example'})
    self.assertEqual(response.status,200);self.assertTrue(response.cookies[COOKIE]['secure']);self.assertTrue(response.cookies[COOKIE]['httponly'])
    for origin in ['https://evil.example',str(client.make_url('/')).rstrip('/')]:
     response=await client.post('/api/auth/login',json={},headers={'Origin':origin,'X-Forwarded-Proto':'https','X-Forwarded-Host':'agent.example'})
     self.assertEqual(response.status,403)
 def test_invalid_or_insecure_tls_settings_fail_before_start(self):
  for origin in ['https://agent.example/path','https://user:pass@agent.example','file:///tmp/agent','https://agent.example?token=x']:
   with patch.dict(os.environ,{'PUBLIC_ORIGIN':origin,'SESSION_COOKIE_SECURE':'true'}),self.assertRaises(ValueError):proxy.create_app(object())
  with patch.dict(os.environ,{'PUBLIC_ORIGIN':'https://agent.example','SESSION_COOKIE_SECURE':'false'}),self.assertRaises(ValueError):proxy.create_app(object())
if __name__=='__main__':unittest.main()
