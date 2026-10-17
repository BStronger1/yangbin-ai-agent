import { lookup } from 'node:dns/promises';
import { isIP, BlockList } from 'node:net';
import { Agent, fetch as providerFetch } from 'undici';

const blockedV4 = new BlockList();
for (const [address, prefix] of [['0.0.0.0',8],['10.0.0.0',8],['100.64.0.0',10],['127.0.0.0',8],['169.254.0.0',16],['172.16.0.0',12],['192.0.0.0',24],['192.0.2.0',24],['192.88.99.0',24],['192.168.0.0',16],['198.18.0.0',15],['198.51.100.0',24],['203.0.113.0',24],['224.0.0.0',3]]) blockedV4.addSubnet(address, prefix, 'ipv4');
const globalV6 = new BlockList(); globalV6.addSubnet('2000::', 3, 'ipv6');
const blockedV6 = new BlockList();
for (const [address, prefix] of [['2001::',23],['2001:db8::',32],['2002::',16],['3fff::',20]]) blockedV6.addSubnet(address, prefix, 'ipv6');
export function isPublicAddress(address) {
  const version = isIP(address);
  return version === 4 ? !blockedV4.check(address, 'ipv4') : version === 6 && globalV6.check(address, 'ipv6') && !blockedV6.check(address, 'ipv6');
}
export function createRedactor(secret) {
  const candidates = [...new Set([secret, encodeURIComponent(secret), JSON.stringify(secret).slice(1,-1)])].filter(Boolean).sort((a,b) => b.length-a.length);
  const text = value => { let result = String(value); for (const key of candidates) result = result.split(key).join('[redacted]'); return result; };
  const mutate = value => {
    if (typeof value === 'string') return text(value);
    if (Array.isArray(value)) { for (let i=0; i<value.length; i++) value[i] = mutate(value[i]); }
    else if (value && typeof value === 'object') for (const key of Object.keys(value)) value[key] = mutate(value[key]);
    return value;
  };
  const stream = () => {
    let pending = '';
    return {
      push(delta) {
        pending = text(pending + delta);
        let keep = 0;
        for (const candidate of candidates) for (let length=1; length<candidate.length && length<=pending.length; length++) if (pending.endsWith(candidate.slice(0,length))) keep = Math.max(keep,length);
        const result = pending.slice(0,pending.length-keep); pending = pending.slice(pending.length-keep); return result;
      },
      flush() { const result=text(pending); pending=''; return result; },
    };
  };
  return { text, mutate, stream };
}
// Each TCP connection resolves and validates in its own lookup callback; fetch never re-resolves
// the checked address. Redirects are rejected instead of forwarding credentials to another host.
export function createModelTransport(baseUrl, { allowPrivate = false, apiKey, resolveHost = lookup } = {}) {
  const base = new URL(baseUrl);
  const redactor = createRedactor(apiKey || '');
  const assertAddress = address => { if (!allowPrivate && !isPublicAddress(address)) throw new Error('Model endpoint must resolve to public IP addresses'); };
  const dispatcher = new Agent({ connect: {
    lookup(host, options, callback) {
      Promise.resolve(resolveHost(host, { all: true, verbatim: true })).then(addresses => {
        if (!addresses.length) throw new Error('Model endpoint has no DNS addresses');
        for (const item of addresses) assertAddress(item.address);
        const selected = options.family ? addresses.filter(x => x.family === options.family) : addresses;
        if (!selected.length) throw new Error('Model endpoint has no matching IP family');
        if (options.all) callback(null, selected); else callback(null, selected[0].address, selected[0].family);
      }).catch(error => callback(new Error(redactor.text(error.message))));
    },
  } });
  return {
    async fetch(input, init) {
      const target = new URL(typeof input === 'string' || input instanceof URL ? input : input.url);
      if (target.origin !== base.origin || target.username || target.password) throw new Error('Cross-origin model requests are disabled');
      if (!allowPrivate && target.protocol !== 'https:') throw new Error('Public model endpoints require HTTPS');
      const host = target.hostname.replace(/^\[|\]$/g, '');
      if (isIP(host)) assertAddress(host);
      try {
        const response = await providerFetch(input, { ...init, redirect: 'manual', dispatcher });
        if (response.status >= 300 && response.status < 400) { await response.body?.cancel(); throw new Error('Model redirects are disabled'); }
        if (!response.ok) {
          const reader = response.body?.getReader(); let size = 0, chunks = [];
          if (reader) { while (size < 65536) { const part = await reader.read(); if (part.done) break; chunks.push(part.value); size += part.value.length; } await reader.cancel(); }
          const body = redactor.text(Buffer.concat(chunks).subarray(0,65536).toString('utf8'));
          const headers = new Headers(response.headers); headers.delete('content-length'); headers.delete('content-encoding');
          return new Response(body, { status: response.status, statusText: response.statusText, headers });
        }
        return response;
      } catch (error) { throw new Error(redactor.text(error.message)); }
    },
    close: () => dispatcher.close(),
  };
}
