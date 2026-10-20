"""Local readiness probe with real TLS hostname and chain verification.

Connect to the configured local listener directly; no DNS hairpin or proxy env is
needed. HTTPS still verifies PUBLIC_ORIGIN's hostname using the OS trust store.
"""
import http.client
import ipaddress
import json
import socket
import ssl
from urllib.parse import urlsplit


def listener(env):
    host=env.get('PUBLIC_BIND','127.0.0.1')
    if host=='0.0.0.0':host='127.0.0.1'
    if host=='::':host='::1'
    port=int(env.get('PUBLIC_PORT','18825'))
    if not 1<=port<=65535:raise ValueError('Invalid gateway port')
    # Bind is deliberately an IP; certificate identity comes from PUBLIC_ORIGIN.
    ipaddress.ip_address(host)
    return host,port


class VerifiedLocalHttps(http.client.HTTPSConnection):
    def __init__(self,hostname,address,timeout):
        super().__init__(hostname,port=address[1],timeout=timeout,context=ssl.create_default_context())
        self.address=address
    def connect(self):
        raw=socket.create_connection(self.address,self.timeout)
        try:self.sock=self._context.wrap_socket(raw,server_hostname=self.host)
        except BaseException:
            raw.close()
            raise


def gateway_ready(env,timeout=2):
    """Return true only for the local gateway's unauthenticated healthy JSON reply."""
    address=listener(env)
    mode=env.get('GATEWAY_TLS_MODE','off')
    if mode not in ('off','direct','reverse-proxy'):raise ValueError('Invalid TLS mode')
    origin=urlsplit(env.get('PUBLIC_ORIGIN',''))
    if mode=='direct':
        if origin.scheme!='https' or not origin.hostname or origin.username or origin.password or origin.path or origin.query or origin.fragment:
            raise ValueError('Direct TLS readiness requires exact HTTPS PUBLIC_ORIGIN')
        connection=VerifiedLocalHttps(origin.hostname,address,timeout)
    else:
        connection=http.client.HTTPConnection(address[0],address[1],timeout=timeout)
    try:
        headers={'Host':origin.netloc} if origin.hostname else {}
        connection.request('GET','/api/auth/me',headers=headers)
        response=connection.getresponse()
        if response.status!=200:return False
        raw=response.read(65537)
        if len(raw)>65536:return False
        body=json.loads(raw)
        return isinstance(body,dict) and 'user' in body and body['user'] is None
    finally:connection.close()
