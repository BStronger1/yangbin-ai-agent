"""Authenticated frontend and streaming API proxy for a personal deployment."""
import asyncio
from accounts import Accounts,RateLimit,COOKIE,auth_api
import hmac
import os
from pathlib import Path
from aiohttp import web, ClientSession, ClientTimeout, ClientError

ROOT = Path(__file__).resolve().parents[2]
HOP = {"connection", "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade"}

@web.middleware
async def authenticate(request, handler):
    # Static assets and the SPA shell are public; every data API needs a session.
    if not request.path.startswith('/api/'):
        return await handler(request)
    origin=request.headers.get('Origin')
    if (origin and origin != request.scheme+'://'+request.host) or request.headers.get('Sec-Fetch-Site')=='cross-site':
        return web.json_response({'message':'请求来源不受信任'},status=403)
    action=request.path.removeprefix('/api/auth/')
    public=request.path in ('/api/auth/login','/api/auth/register','/api/auth/me')
    if request.method not in ('GET','HEAD','OPTIONS') and not origin:
        return web.json_response({'message':'缺少请求来源'},status=403)
    user=request.app['accounts'].session(request.cookies.get(COOKIE,''))
    request['user']=user
    if not public and not user:return web.json_response({'message':'请先登录'},status=401)
    if user and request.method not in ('GET','HEAD','OPTIONS') and action not in ('login','register'):
        if not hmac.compare_digest(request.headers.get('X-CSRF-Token',''),user['csrf']):
            return web.json_response({'message':'会话校验失败，请刷新页面'},status=403)
    response=await handler(request)
    response.headers['Cache-Control']='no-store'
    response.headers['X-Content-Type-Options']='nosniff'
    return response

async def clients(app):
    async with ClientSession(timeout=ClientTimeout(total=360), auto_decompress=False) as client:
        app["client"] = client
        yield

async def api(request):
    # authenticate already enforces same-origin; do not trigger CORS again on the loopback hop.
    headers = {k: v for k, v in request.headers.items() if k.lower() not in HOP | {"host", "authorization", "content-length", "cookie", "x-internal-token", "origin", "x-user-id", "x-user-role", "x-csrf-token"}}
    if os.environ.get("INTERNAL_API_TOKEN"): headers["X-Internal-Token"] = os.environ["INTERNAL_API_TOKEN"]
    headers["X-User-Id"] = request["user"]["id"]
    headers["X-User-Role"] = request["user"]["role"]
    try:
        async with request.app["client"].request(request.method,
                "http://127.0.0.1:" + os.environ.get("SERVER_PORT", "18126") + request.raw_path,
                headers=headers, data=await request.read(), allow_redirects=False) as upstream:
            response = web.StreamResponse(status=upstream.status)
            for key,value in upstream.headers.items():
                if key.lower() not in HOP: response.headers.add(key,value)
            response.headers["Cache-Control"] = "no-store"
            response.headers["X-Content-Type-Options"] = "nosniff"
            await response.prepare(request)
            async for chunk in upstream.content.iter_any(): await response.write(chunk)
            await response.write_eof()
            return response
    except ClientError:
        raise web.HTTPBadGateway(text="Backend unavailable")

async def frontend(request):
    root = (ROOT / "frontend").resolve()
    target = (root / request.match_info["path"]).resolve()
    if not target.is_relative_to(root): raise web.HTTPNotFound()
    if not target.is_file():
        if target.suffix: raise web.HTTPNotFound()
        target = root / "index.html"
    return web.FileResponse(target, headers={"Cache-Control":"no-store", "X-Content-Type-Options":"nosniff"})

def create_app(accounts=None):
    app=web.Application(middlewares=[authenticate],client_max_size=2*1024*1024)
    app['accounts']=accounts or Accounts(ROOT/'tmp/auth/accounts.sqlite',os.environ['ACCESS_USER'],os.environ['ACCESS_PASSWORD'])
    app['rate_limit']=RateLimit();app['password_slots']=asyncio.Semaphore(2)
    app.cleanup_ctx.append(clients)
    app.router.add_route('*','/api/auth/{action:.*}',auth_api)
    app.router.add_route('*','/api/{path:.*}',api)
    app.router.add_get('/{path:.*}',frontend)
    return app

if __name__ == '__main__':
    web.run_app(create_app(),host=os.environ.get('PUBLIC_BIND','127.0.0.1'),port=int(os.environ.get('PUBLIC_PORT','18825')),access_log=None)
