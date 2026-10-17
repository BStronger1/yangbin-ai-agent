"""Authenticated frontend and streaming API proxy for a personal deployment."""
import asyncio
from accounts import Accounts,RateLimit,COOKIE,auth_api
import hmac
import os
from urllib.parse import urlsplit
from pathlib import Path
from aiohttp import web, ClientSession, ClientTimeout, ClientError

ROOT = Path(__file__).resolve().parents[2]
HOP = {"connection", "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade"}

@web.middleware
async def authenticate(request, handler):
    # Static assets and the SPA shell are public; every data API needs a session.
    if not request.path.startswith(('/api/', '/ppt/')):
        return await handler(request)
    origin=request.headers.get('Origin')
    expected_origin=request.app.get('public_origin') or request.scheme+'://'+request.host
    if (origin and origin != expected_origin) or request.headers.get('Sec-Fetch-Site')=='cross-site':
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

async def ppt_status(request):
    enabled = bool(os.environ.get("NARRATIVE_PORT")) and len(os.environ.get("AGENT_BRIDGE_SECRET", "")) >= 32
    if not enabled:
        return web.json_response({"enabled": False, "ready": False})
    try:
        async with request.app["client"].get(
                "http://127.0.0.1:" + os.environ["NARRATIVE_PORT"] + "/api/v1/health",
                headers={"X-Agent-Bridge-Token": os.environ["AGENT_BRIDGE_SECRET"],
                         "X-Agent-Owner": request["user"]["id"]},
                timeout=ClientTimeout(total=3)) as result:
            body = await result.json()
            return web.json_response({"enabled": True, "ready": result.status == 200 and body.get("database") == "ok" and body.get("redis") == "ok"})
    except (ClientError, asyncio.TimeoutError):
        return web.json_response({"enabled": True, "ready": False})


async def narrative_access(request):
    headers = {"X-Internal-Token": os.environ.get("INTERNAL_API_TOKEN", ""),
               "X-User-Id": request["user"]["id"], "X-User-Role": request["user"]["role"]}
    try:
        async with request.app["client"].get("http://127.0.0.1:" + os.environ.get("SERVER_PORT", "18126")
                + "/api/ai/work/partners/presentation/access", headers=headers, timeout=ClientTimeout(total=5)) as response:
            if response.status != 200: raise web.HTTPServiceUnavailable(text="无法确认伙伴授权")
            policy = await response.json()
            if policy.get("enabled") is not True:
                raise web.HTTPForbidden(text="NarrativePPT访问已限制，请在伙伴详情的管理中调整授权")
    except (ClientError, asyncio.TimeoutError, ValueError):
        raise web.HTTPServiceUnavailable(text="无法确认伙伴授权")

async def narrative(request):
    port = os.environ.get("NARRATIVE_PORT", "")
    secret = os.environ.get("AGENT_BRIDGE_SECRET", "")
    if not port.isdigit() or len(secret) < 32:
        return web.json_response({"detail": "演示工作台尚未启用"}, status=503)
    await narrative_access(request)
    suffix = request.raw_path.removeprefix("/ppt")
    headers = {k: v for k, v in request.headers.items()
               if k.lower() not in HOP | {"host", "authorization", "content-length", "cookie",
                   "origin", "x-csrf-token", "x-user-id", "x-user-role",
                   "x-agent-owner", "x-agent-bridge-token", "x-internal-token"}}
    headers["X-Agent-Owner"] = request["user"]["id"]
    headers["X-Agent-Bridge-Token"] = secret
    try:
        async with request.app["client"].request(request.method,
                "http://127.0.0.1:" + port + suffix, headers=headers,
                data=await request.read(), allow_redirects=False,
                timeout=ClientTimeout(total=None, sock_connect=5, sock_read=660)) as upstream:
            response = web.StreamResponse(status=upstream.status)
            for key, value in upstream.headers.items():
                if key.lower() not in HOP | {"set-cookie", "location"}:
                    response.headers.add(key, value)
            if "Location" in upstream.headers:
                location = upstream.headers["Location"]
                if not location.startswith("/") or location.startswith("//"):
                    raise web.HTTPBadGateway(text="Unexpected upstream redirect")
                response.headers["Location"] = "/ppt" + location
            response.headers["Cache-Control"] = "no-store"
            response.headers["Content-Security-Policy"] = "frame-ancestors 'self'"
            await response.prepare(request)
            async for chunk in upstream.content.iter_any():
                await response.write(chunk)
            await response.write_eof()
            return response
    except (ClientError, asyncio.TimeoutError):
        raise web.HTTPBadGateway(text="演示服务暂时不可用")


async def frontend(request):
    root = Path(os.environ.get("AGENT_FRONTEND_DIR", str(ROOT / "frontend"))).resolve()
    target = (root / request.match_info["path"]).resolve()
    if not target.is_relative_to(root): raise web.HTTPNotFound()
    if not target.is_file():
        if target.suffix: raise web.HTTPNotFound()
        target = root / "index.html"
    return web.FileResponse(target, headers={"Cache-Control":"no-store", "X-Content-Type-Options":"nosniff"})

def create_app(accounts=None):
    app=web.Application(middlewares=[authenticate],client_max_size=12*1024*1024)
    public_origin=os.environ.get('PUBLIC_ORIGIN','').rstrip('/')
    if public_origin:
        parsed=urlsplit(public_origin)
        if parsed.scheme not in ('http','https') or not parsed.hostname or parsed.username or parsed.password or parsed.path or parsed.query or parsed.fragment:
            raise ValueError('PUBLIC_ORIGIN must be an exact http(s) origin without path or credentials')
        if parsed.scheme=='https' and os.environ.get('SESSION_COOKIE_SECURE')!='true':
            raise ValueError('HTTPS PUBLIC_ORIGIN requires SESSION_COOKIE_SECURE=true')
    app['public_origin']=public_origin
    app['accounts']=accounts or Accounts(ROOT/'tmp/auth/accounts.sqlite',os.environ['ACCESS_USER'],os.environ['ACCESS_PASSWORD'])
    app['rate_limit']=RateLimit();app['password_slots']=asyncio.Semaphore(2)
    app.cleanup_ctx.append(clients)
    app.router.add_route('*','/api/auth/{action:.*}',auth_api)
    app.router.add_get('/api/ppt/status',ppt_status)
    app.router.add_route('*','/ppt/{path:.*}',narrative)
    app.router.add_route('*','/api/{path:.*}',api)
    app.router.add_get('/{path:.*}',frontend)
    return app

if __name__ == '__main__':
    web.run_app(create_app(),host=os.environ.get('PUBLIC_BIND','127.0.0.1'),port=int(os.environ.get('PUBLIC_PORT','18825')),access_log=None)
