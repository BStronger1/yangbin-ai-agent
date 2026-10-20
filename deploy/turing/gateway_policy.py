"""Bounded, process-local HTTP admission and content-free gateway telemetry.

Not a distributed quota or a background-job limiter. No bodies, queries, account
identifiers, IPs, or upstream exception strings enter metrics or logs.
"""
import asyncio
import ipaddress
import json
import logging
from logging.handlers import RotatingFileHandler
from pathlib import Path
import os
import re
import ssl
import time
import uuid
from collections import Counter, deque
from aiohttp import web

LOG = logging.getLogger('yangbin.gateway')

class PrivateRotatingHandler(RotatingFileHandler):
    def _open(self):
        descriptor=os.open(self.baseFilename,os.O_WRONLY|os.O_CREAT|os.O_APPEND,0o600)
        return open(descriptor,'a',encoding=self.encoding,errors=self.errors)

def configure_log(root,filename='gateway.jsonl'):
    if filename not in ('gateway.jsonl','gateway-public.jsonl'):
        raise ValueError('Unknown gateway log')
    folder=Path(root)/'logs';folder.mkdir(parents=True,exist_ok=True,mode=0o700)
    path=folder/filename
    handler=PrivateRotatingHandler(path,maxBytes=2_000_000,backupCount=3,encoding='utf-8')
    path.chmod(0o600)
    handler.setFormatter(logging.Formatter('%(message)s'))
    LOG.addHandler(handler);LOG.setLevel(logging.INFO);LOG.propagate=False

UNTRUSTED = {'x-request-id', 'traceparent', 'tracestate', 'baggage', 'forwarded',
             'x-forwarded-for', 'x-forwarded-host', 'x-forwarded-proto', 'x-real-ip'}


def positive(name, default):
    value = int(os.environ.get(name, default))
    if value < 1 or value > 100000:
        raise ValueError(name + ' must be between 1 and 100000')
    return value


def route_group(path):
    # Fixed-cardinality categories; never log user-supplied routes or query strings.
    for prefix, group in (('/api/auth/', 'auth'), ('/api/ops/', 'operations'),
            ('/api/ai/coding', 'coding'), ('/api/ai/work', 'partners'),
            ('/api/ai/mcp', 'mcp'), ('/api/ai/models', 'models'),
            ('/api/ai/love_app', 'chat'), ('/api/ai/manus', 'chat'),
            ('/ppt/', 'presentation'), ('/api/', 'api')):
        if path.startswith(prefix):
            return group
    return 'static'


def expensive(request):
    path = request.path
    if path.startswith(('/api/ai/manus/chat', '/api/ai/love_app/chat')):
        return True
    if request.method not in ('POST', 'PUT', 'PATCH'):
        return False
    if path.startswith('/ppt/api/'):
        return True  # Conservative for externally managed generation endpoints.
    return (path in ('/api/ai/work/runs', '/api/ai/plans', '/api/ai/mcp/call') or
            bool(re.fullmatch(r'/api/ai/(coding/sessions/[^/]+/turns|work/partners/[^/]+/(messages|jobs)|tasks/[^/]+/(start|retry)|plans/[^/]+/(revise|repair))', path)))


class Admission:
    def __init__(self):
        self.limits = dict(globalConcurrent=positive('GATEWAY_MAX_CONCURRENT', 64),
            userConcurrent=positive('GATEWAY_USER_CONCURRENT', 12),
            modelConcurrent=positive('GATEWAY_MODEL_CONCURRENT', 8),
            userModelConcurrent=positive('GATEWAY_USER_MODEL_CONCURRENT', 2),
            requestsPerMinute=positive('GATEWAY_USER_RPM', 240),
            modelRequestsPerMinute=positive('GATEWAY_USER_MODEL_RPM', 20))
        self.active = Counter()
        self.users = {}
        self.total = 0
        self.models = 0

    def acquire(self, owner, model):
        now = time.monotonic()
        # Bound even long-running deployments with many historical accounts.
        if len(self.users) >= 10000:
            self.users = {k: v for k, v in self.users.items() if v[0] and v[0][-1] > now - 60 or self.active[k]}
        if owner not in self.users and len(self.users) >= 10000:
            return 'capacity'
        all_events, model_events = self.users.setdefault(owner, (deque(), deque()))
        for events in (all_events, model_events):
            while events and events[0] <= now - 60:
                events.popleft()
        limits = self.limits
        if len(all_events) >= limits['requestsPerMinute'] or model and len(model_events) >= limits['modelRequestsPerMinute']:
            return 'rate'
        if self.total >= limits['globalConcurrent'] or self.active[owner] >= limits['userConcurrent']:
            return 'capacity'
        if model and (self.models >= limits['modelConcurrent'] or self.active[owner + ':model'] >= limits['userModelConcurrent']):
            return 'capacity'
        all_events.append(now)
        if model:
            model_events.append(now)
            self.models += 1
            self.active[owner + ':model'] += 1
        self.total += 1
        self.active[owner] += 1
        return None

    def release(self, owner, model):
        self.total -= 1
        for key in (owner, owner + ':model') if model else (owner,):
            self.active[key] -= 1
            if not self.active[key]:
                del self.active[key]
        if model:
            self.models -= 1


class Metrics:
    def __init__(self):
        self.started = time.monotonic()
        self.counts = Counter()
        self.rejected = Counter()
        self.latencies = {}

    def observe(self, group, status, elapsed):
        self.counts[(group, str(status // 100) + 'xx')] += 1
        self.latencies.setdefault(group, deque(maxlen=512)).append(elapsed)

    def snapshot(self):
        return {'scope': 'single-gateway-process', 'uptimeSeconds': int(time.monotonic() - self.started),
            'requests': [{'route': group, 'statusClass': status, 'count': count}
                for (group, status), count in sorted(self.counts.items())],
            'rejected': dict(self.rejected), 'latency': [{'route': group, 'sampleCount': len(values),
                'p95Ms': round(sorted(values)[max(0, (len(values) * 95 + 99) // 100 - 1)], 2)}
                for group, values in sorted(self.latencies.items())], 'latencyWindow': 'last-512-completed-per-route'}


@web.middleware
async def trace(request, handler):
    request['request_id'] = uuid.uuid4().hex
    start = time.monotonic()
    status = 500
    try:
        try:
            response = await handler(request)
        except web.HTTPException as exc:
            response = web.Response(status=exc.status,body=exc.body,headers=exc.headers)
        status = response.status
        return response
    except asyncio.CancelledError:
        status = 499
        raise
    except Exception:
        # Never let aiohttp's default traceback expose request/upstream secrets.
        status = 500
        return web.json_response({'message': '请求处理失败', 'requestId': request['request_id']}, status=500)
    finally:
        elapsed = (time.monotonic() - start) * 1000
        group = route_group(request.path)
        request.app['gateway_metrics'].observe(group, status, elapsed)
        if group != 'static':
            LOG.info(json.dumps({'event': 'http_complete', 'time': time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime()), 'requestId': request['request_id'],
                'route': group, 'status': status, 'durationMs': round(elapsed, 2)}, separators=(',', ':')))


async def response_headers(request, response):
    # Signal also runs for already streaming proxy responses.
    response.headers['X-Request-Id'] = request['request_id']
    response.headers['X-Content-Type-Options'] = 'nosniff'
    if request.path.startswith(('/api/','/ppt/')):
        response.headers['Cache-Control'] = 'no-store'
    if request.app['transport']['production']:
        response.headers['Strict-Transport-Security'] = 'max-age=31536000'


@web.middleware
async def admit(request, handler):
    transport = request.app['transport']
    if transport['production'] and transport['mode'] == 'reverse-proxy':
        try:
            local = ipaddress.ip_address(request.remote).is_loopback
        except (ValueError, TypeError):
            local = False
        if not local:
            return web.json_response({'message': '必须通过受信任 TLS 入口访问'}, status=403)
    user = request.get('user')
    if not user or request.path.startswith(('/api/auth/', '/api/ops/')) or not request.path.startswith(('/api/', '/ppt/')):
        return await handler(request)
    guard = request.app['admission']
    model = expensive(request)
    reason = guard.acquire(user['id'], model)
    if reason:
        request.app['gateway_metrics'].rejected[reason] += 1
        return web.json_response({'message': '请求过于频繁，请稍后重试' if reason == 'rate' else '当前服务繁忙，请稍后重试',
            'code': 'RATE_LIMITED' if reason == 'rate' else 'CAPACITY_LIMIT', 'requestId': request['request_id']},
            status=429 if reason == 'rate' else 503, headers={'Retry-After': '60' if reason == 'rate' else '2'})
    try:
        return await handler(request)
    finally:
        guard.release(user['id'], model)


def transport_settings(public_origin):
    production_flag = os.environ.get('AGENT_PRODUCTION_MODE', 'false')
    if production_flag not in ('true','false'):
        raise ValueError('AGENT_PRODUCTION_MODE must be true or false')
    production = production_flag == 'true'
    mode = os.environ.get('GATEWAY_TLS_MODE', 'off')
    if mode not in ('off', 'direct', 'reverse-proxy'):
        raise ValueError('GATEWAY_TLS_MODE must be off, direct, or reverse-proxy')
    if production and (not public_origin.startswith('https://') or os.environ.get('SESSION_COOKIE_SECURE') != 'true' or mode == 'off'):
        raise ValueError('Production requires HTTPS PUBLIC_ORIGIN, secure cookies, and TLS mode')
    if mode == 'reverse-proxy':
        try:
            loopback = ipaddress.ip_address(os.environ.get('PUBLIC_BIND', '127.0.0.1')).is_loopback
        except ValueError:
            loopback = False
        if not loopback:
            raise ValueError('TLS reverse proxy requires loopback PUBLIC_BIND')
    context = None
    if mode == 'direct':
        cert = os.environ.get('GATEWAY_TLS_CERT')
        key = os.environ.get('GATEWAY_TLS_KEY')
        if not cert or not key:
            raise ValueError('Direct TLS requires GATEWAY_TLS_CERT and GATEWAY_TLS_KEY')
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.load_cert_chain(cert, key)
    return {'production': production, 'mode': mode, 'tlsConfigured': mode != 'off'}, context


async def operations(request):
    if request['user']['role'] != 'admin':
        raise web.HTTPForbidden()
    if request.path.endswith('/metrics'):
        return web.json_response(request.app['gateway_metrics'].snapshot())
    guard = request.app['admission']
    return web.json_response({'gateway': 'ready', 'transport': request.app['transport'],
        'admission': {'scope': 'http-requests-single-process', 'active': guard.total,
            'modelActive': guard.models, 'limits': guard.limits},
        'requestId': request['request_id'], 'limitations': ['Not a backend or model health probe',
            'Background jobs require service-side admission', 'TLS certificate trust must be verified externally']})
