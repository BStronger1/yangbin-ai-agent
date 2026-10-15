"""Operator smoke scripts use the normal session API, never forwarded identity headers."""
import http.cookiejar,json,urllib.request
class SessionClient:
 def __init__(self,base,username,password):
  self.base=base;self.csrf='';self.opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
  self.user=self.call('/api/auth/login','POST',{'username':username,'password':password})['user'];self.csrf=self.user['csrf']
 def call(self,path,method='GET',data=None,content_type='application/json'):
  raw=None if data is None else data if isinstance(data,bytes) else json.dumps(data,ensure_ascii=False).encode()
  request=urllib.request.Request(self.base+path,data=raw,method=method,headers={'Origin':self.base,'X-CSRF-Token':self.csrf,'Content-Type':content_type})
  with self.opener.open(request,timeout=180) as response:
   body=response.read();return json.loads(body) if 'json' in response.headers.get('Content-Type','') else body
