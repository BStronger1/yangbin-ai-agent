import { lookup } from 'node:dns/promises';
import { isIP } from 'node:net';
import { Agent, fetch } from 'undici';
import { isPublicAddress } from '../model-transport.mjs';

export function endpoint(value, { testLoopback = false } = {}) {
  const url = new URL(value);
  const host = url.hostname.replace(/^\[|\]$/g, '');
  const local = testLoopback && ['127.0.0.1', '::1'].includes(host);
  if (url.username || url.password || url.hash || url.search || (url.protocol !== 'https:' && !(local && url.protocol === 'http:')) || (isIP(host) && !local && !isPublicAddress(host))) throw new Error('MCP_ENDPOINT_DENIED');
  return url;
}

/** No ambient proxy, redirects, cookies, endpoint switching, retry or unbounded body. */
export function createMcpTransport(urlValue, { testLoopback = false, resolveHost = lookup, maxBytes = 262144, timeoutMs = 15000, signal, token = async () => null } = {}) {
  const url = endpoint(urlValue, { testLoopback });
  const dispatcher = new Agent({ connect: { lookup(host, options, cb) {
    Promise.resolve(resolveHost(host, { all: true, verbatim: true })).then(addresses => {
      if (!addresses.length || addresses.some(a => !isPublicAddress(a.address))) throw new Error('MCP_DNS_DENIED');
      const selected = options.family ? addresses.filter(a => a.family === options.family) : addresses;
      if (!selected.length) throw new Error('MCP_DNS_DENIED');
      options.all ? cb(null, selected) : cb(null, selected[0].address, selected[0].family);
    }).catch(() => cb(new Error('MCP_DNS_DENIED')));
  } } });
  const sent = new Set();
  return {
    async fetch(input, init = {}) {
      const target = new URL(input instanceof Request ? input.url : input);
      if (target.href !== url.href) throw new Error('MCP_ENDPOINT_CHANGED');
      const request = typeof init.body === 'string' ? (() => { try { return JSON.parse(init.body); } catch { return null; } })() : null;
      if (request?.method === 'tools/call') {
        const id = String(request.id);
        if (sent.has(id)) throw new Error('MCP_REPLAY_DENIED');
        sent.add(id);
      }
      const headers = new Headers(init.headers);
      headers.delete('cookie'); headers.delete('authorization');
      const credential = await token();
      if (credential) headers.set('Authorization', 'Bearer ' + credential);
      const boundedSignal = AbortSignal.any([signal, init.signal, AbortSignal.timeout(timeoutMs)].filter(Boolean));
      const response = await fetch(target, { ...init, headers, dispatcher, redirect: 'manual', signal: boundedSignal });
      if (response.status >= 300 && response.status < 400) { await response.body?.cancel(); throw new Error('MCP_REDIRECT_DENIED'); }
      if (!response.body) return response;
      const reader = response.body.getReader(); let bytes = 0;
      const body = new ReadableStream({
        async pull(controller) {
          try { const item = await reader.read(); if (item.done) return controller.close(); bytes += item.value.byteLength; if (bytes > maxBytes) throw new Error('MCP_RESPONSE_TOO_LARGE'); controller.enqueue(item.value); }
          catch (error) { await reader.cancel().catch(() => {}); controller.error(error); }
        }, cancel: reason => reader.cancel(reason),
      });
      const safeHeaders = new Headers(response.headers); safeHeaders.delete('content-length'); safeHeaders.delete('content-encoding');
      return new Response(body, { status: response.status, headers: safeHeaders });
    },
    close: () => dispatcher.destroy(),
  };
}

