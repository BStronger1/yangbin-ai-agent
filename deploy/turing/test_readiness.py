import json
import ssl
import threading
import unittest
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from unittest.mock import Mock,patch
from readiness import gateway_ready,listener,VerifiedLocalHttps


class ReadinessTest(unittest.TestCase):
    def test_wildcard_bind_uses_loopback_and_invalid_port_fails(self):
        self.assertEqual(listener({'PUBLIC_BIND':'0.0.0.0','PUBLIC_PORT':'18825'}),('127.0.0.1',18825))
        self.assertEqual(listener({'PUBLIC_BIND':'::'}),('::1',18825))
        for port in ['0','65536','invalid']:
            with self.assertRaises(ValueError):listener({'PUBLIC_PORT':port})

    def test_direct_tls_requires_valid_origin_and_never_uses_http(self):
        for origin in ['', 'http://yangbinai.homes','https://user:secret@yangbinai.homes','https://yangbinai.homes/path']:
            with self.assertRaises(ValueError):gateway_ready({'GATEWAY_TLS_MODE':'direct','PUBLIC_ORIGIN':origin})
        with patch('readiness.VerifiedLocalHttps') as factory:
            connection=factory.return_value
            connection.getresponse.return_value.status=200
            connection.getresponse.return_value.read.return_value=b'{"user":null}'
            self.assertTrue(gateway_ready({'GATEWAY_TLS_MODE':'direct','PUBLIC_ORIGIN':'https://yangbinai.homes','PUBLIC_BIND':'0.0.0.0'}))
            factory.assert_called_once_with('yangbinai.homes',('127.0.0.1',18825),2)
            connection.request.assert_called_once_with('GET','/api/auth/me',headers={'Host':'yangbinai.homes'})
            connection.close.assert_called_once()

    def test_ssl_defaults_require_trust_and_hostname(self):
        connection=VerifiedLocalHttps('yangbinai.homes',('127.0.0.1',18825),2)
        self.assertEqual(connection._context.verify_mode,ssl.CERT_REQUIRED)
        self.assertTrue(connection._context.check_hostname)

    def test_ssl_hostname_and_failure_close_socket_without_fallback(self):
        raw=Mock();context=Mock();context.wrap_socket.side_effect=ssl.SSLCertVerificationError('test-untrusted')
        with patch('readiness.ssl.create_default_context',return_value=context),patch('readiness.socket.create_connection',return_value=raw) as connect:
            connection=VerifiedLocalHttps('yangbinai.homes',('127.0.0.1',18825),3)
            with self.assertRaises(ssl.SSLCertVerificationError):connection.connect()
            connect.assert_called_once_with(('127.0.0.1',18825),3)
            context.wrap_socket.assert_called_once_with(raw,server_hostname='yangbinai.homes')
            raw.close.assert_called_once()

    def test_actual_local_http_auth_health_and_response_validation(self):
        class Handler(BaseHTTPRequestHandler):
            payload=b'{"user":null}';status=200
            def log_message(self,*args):pass
            def do_GET(self):
                self.send_response(self.status);self.end_headers();self.wfile.write(self.payload)
        server=ThreadingHTTPServer(('127.0.0.1',0),Handler)
        thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
        env={'PUBLIC_BIND':'0.0.0.0','PUBLIC_PORT':str(server.server_port)}
        try:
            self.assertTrue(gateway_ready(env))
            for value in [b'{}',b'{"user":{"id":"admin"}}',b'[]',b'"not-object"',b' '*65537]:
                Handler.payload=value;self.assertFalse(gateway_ready(env))
            Handler.payload=b'{"user":null}';Handler.status=503;self.assertFalse(gateway_ready(env))
        finally:server.shutdown();server.server_close();thread.join()

    def test_reverse_proxy_local_probe_does_not_claim_external_tls(self):
        with patch('readiness.http.client.HTTPConnection') as factory:
            factory.return_value.getresponse.return_value.status=200
            factory.return_value.getresponse.return_value.read.return_value=b'{"user":null}'
            self.assertTrue(gateway_ready({'GATEWAY_TLS_MODE':'reverse-proxy','PUBLIC_ORIGIN':'https://yangbinai.homes'}))
            factory.assert_called_once_with('127.0.0.1',18825,timeout=2)


if __name__=='__main__':unittest.main()
