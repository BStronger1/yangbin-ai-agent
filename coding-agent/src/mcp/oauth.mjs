import { randomBytes, createHash } from 'node:crypto';
import { endpoint, createMcpTransport } from './transport.mjs';

const key = (identity, id) => {
  if (!identity?.accountId || !identity?.projectId || !id) throw new Error('MCP_OAUTH_IDENTITY_REQUIRED');
  return identity.accountId + ':' + identity.projectId + ':' + id;
};
/** Trusted web backend only. Never expose tokens, PKCE verifier, or metadata to the model. */
export function createMcpOAuthBroker({ configurations, store = new Map(), testLoopback = false, now = Date.now }) {
  const configured = new Map(), pending = new Map();
  for (const raw of configurations) {
    const c = structuredClone(raw);
    for (const field of ['resource', 'issuer', 'protectedResourceMetadataUrl', 'authorizationMetadataUrl', 'authorizationEndpoint', 'tokenEndpoint', 'redirectUri']) endpoint(c[field], {testLoopback});
    if (!c.clientId || !Array.isArray(c.scopes) || c.scopes.some(s => typeof s !== 'string' || /\s/.test(s))) throw new Error('MCP_OAUTH_CONFIG_DENIED');
    configured.set(key(c, c.serverId), c);
  }
  const config = (identity, serverId) => { const c = configured.get(key(identity,serverId)); if (!c) throw new Error('MCP_OAUTH_SCOPE_DENIED'); return c; };
  async function request(url, init) {
    const network=createMcpTransport(url,{testLoopback,maxBytes:32768,timeoutMs:15000});
    try { const response=await network.fetch(url,init);if(!response.ok)throw new Error('MCP_OAUTH_HTTP_FAILED');return await response.json(); }
    catch {throw new Error('MCP_OAUTH_HTTP_FAILED');} finally {await network.close();}
  }
  async function begin({identity,serverId}) {
    const c=config(identity,serverId);
    // Metadata locations and authorization authority are registered by the administrator,
    // never trusted from a redirect, a model argument, or an arbitrary 401 challenge.
    const resource=await request(c.protectedResourceMetadataUrl);
    if(resource.resource!==c.resource || !resource.authorization_servers?.includes(c.issuer))throw new Error('MCP_OAUTH_RESOURCE_MISMATCH');
    const metadata=await request(c.authorizationMetadataUrl);
    if(metadata.issuer!==c.issuer || metadata.authorization_endpoint!==c.authorizationEndpoint || metadata.token_endpoint!==c.tokenEndpoint || !metadata.code_challenge_methods_supported?.includes('S256'))throw new Error('MCP_OAUTH_ISSUER_MISMATCH');
    const state=randomBytes(32).toString('base64url'), verifier=randomBytes(32).toString('base64url');
    const binding=key(identity,serverId);
    // One pending grant per binding; prevents unbounded outstanding login records.
    for(const [id,p] of pending)if(p.binding===binding || p.expiresAt<=now())pending.delete(id);
    pending.set(state,{binding,c,verifier,expiresAt:now()+300000,requireIss:metadata.authorization_response_iss_parameter_supported===true});
    const url=new URL(c.authorizationEndpoint);
    for(const [k,v]of Object.entries({response_type:'code',client_id:c.clientId,redirect_uri:c.redirectUri,resource:c.resource,scope:c.scopes.join(' '),state,code_challenge:createHash('sha256').update(verifier).digest('base64url'),code_challenge_method:'S256'}))url.searchParams.set(k,v);
    return {authorizationUrl:url.href,expiresAt:now()+300000};
  }
  async function complete({identity,serverId,callbackUrl}) {
    const c=config(identity,serverId), callback=new URL(callbackUrl), expected=new URL(c.redirectUri);
    if(callback.origin!==expected.origin||callback.pathname!==expected.pathname)throw new Error('MCP_OAUTH_CALLBACK_DENIED');
    const state=callback.searchParams.get('state'), p=pending.get(state);
    if(!p||p.binding!==key(identity,serverId)||p.expiresAt<=now())throw new Error('MCP_OAUTH_STATE_DENIED');
    const issuer=callback.searchParams.get('iss');
    if((p.requireIss&&!issuer)||(issuer&&issuer!==c.issuer))throw new Error('MCP_OAUTH_ISSUER_MISMATCH');
    pending.delete(state); // One shot, including unknown token exchange outcomes.
    const code=callback.searchParams.get('code');
    if(callback.searchParams.has('error')||!code||code.length>4096)throw new Error('MCP_OAUTH_CODE_DENIED');
    const body=new URLSearchParams({grant_type:'authorization_code',code,client_id:c.clientId,redirect_uri:c.redirectUri,code_verifier:p.verifier,resource:c.resource});
    const value=await request(c.tokenEndpoint,{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:body.toString()});
    const scopes=typeof value.scope==='string'?value.scope.split(' ').filter(Boolean):c.scopes;
    if(value.token_type?.toLowerCase()!=='bearer'||typeof value.access_token!=='string'||!value.access_token||/[\r\n]/.test(value.access_token)||!Number.isFinite(value.expires_in)||value.expires_in<=0||c.scopes.some(s=>!scopes.includes(s)))throw new Error('MCP_OAUTH_TOKEN_DENIED');
    store.set(p.binding,{accountId:identity.accountId,projectId:identity.projectId,resource:c.resource,issuer:c.issuer,scopes,accessToken:value.access_token,expiresAt:now()+Math.min(value.expires_in,86400)*1000});
    return {connected:true,serverId,expiresAt:store.get(p.binding).expiresAt};
  }
  async function credentialProvider({identity,serverId,resource,issuer}) {
    const c=config(identity,serverId), value=store.get(key(identity,serverId));
    if(c.resource!==resource||c.issuer!==issuer||!value||value.expiresAt<=now())return null;
    return structuredClone(value);
  }
  function revoke({identity,serverId}) {config(identity,serverId);const binding=key(identity,serverId);store.delete(binding);for(const [id,p]of pending)if(p.binding===binding)pending.delete(id);}
  return {begin,complete,credentialProvider,revoke};
}

