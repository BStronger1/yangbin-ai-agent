"""Authenticated frontend and streaming API proxy for a personal deployment."""
import base64
import hmac
import os
from pathlib import Path
from urllib.parse import urlsplit
from aiohttp import web, ClientSession, ClientTimeout, ClientError

ROOT = Path(__file__).resolve().parents[2]
HOP = {"connection", "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade"}

@web.middleware
async def authenticate(request, handler):
    expected = "Basic " + base64.b64encode((os.environ["ACCESS_USER"] + ":" + os.environ["ACCESS_PASSWORD"]).encode()).decode()
    if not hmac.compare_digest(request.headers.get("Authorization", "").encode(), expected.encode()):
        raise web.HTTPUnauthorized(headers={"WWW-Authenticate": 'Basic realm="yangbin AI Agent", charset="UTF-8"'})
    origin = request.headers.get("Origin")
    if (origin and urlsplit(origin).netloc != request.host) or request.headers.get("Sec-Fetch-Site") == "cross-site":
        raise web.HTTPForbidden()
    return await handler(request)

async def clients(app):
    async with ClientSession(timeout=ClientTimeout(total=360), auto_decompress=False) as client:
        app["client"] = client
        yield

async def api(request):
    headers = {k: v for k, v in request.headers.items() if k.lower() not in HOP | {"host", "authorization", "content-length", "cookie", "x-internal-token"}}
    if os.environ.get("INTERNAL_API_TOKEN"): headers["X-Internal-Token"] = os.environ["INTERNAL_API_TOKEN"]
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

app = web.Application(middlewares=[authenticate], client_max_size=2*1024*1024)
app.cleanup_ctx.append(clients)
app.router.add_route("*", "/api/{path:.*}", api)
app.router.add_get("/{path:.*}", frontend)
if __name__ == "__main__":
    web.run_app(app, host=os.environ.get("PUBLIC_BIND", "127.0.0.1"), port=int(os.environ.get("PUBLIC_PORT", "18825")), access_log=None)
