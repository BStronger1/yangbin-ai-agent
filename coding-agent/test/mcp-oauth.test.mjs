import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { once } from 'node:events';
import { createMcpOAuthBroker } from '../src/mcp/oauth.mjs';
const identity={accountId:'a',projectId:'p'};
async function fixture(t) {
  const state={tokenRequests:[],issuerMismatch:false,requireIss:true};let base;
  const server=http.createServer(async(req,res)=>{
    const chunks=[];for await(const c of req)chunks.push(c);const body=Buffer.concat(chunks).toString();res.setHeader('Content-Type','application/json');
    if(req.url==='/resource-metadata')res.end(JSON.stringify({resource:base+'/mcp',authorization_servers:[base]}));
    else if(req.url==='/issuer-metadata')res.end(JSON.stringify({issuer:state.issuerMismatch?'https://evil.example':base,authorization_endpoint:base+'/authorize',token_endpoint:base+'/token',code_challenge_methods_supported:['S256'],authorization_response_iss_parameter_supported:state.requireIss}));
    else if(req.url==='/token'){state.tokenRequests.push(new URLSearchParams(body));res.end(JSON.stringify({access_token:'test-only-token',token_type:'Bearer',expires_in:60,scope:'read'}));}
    else {res.writeHead(404);res.end('{}');}
  });server.listen(0,'127.0.0.1');await once(server,'listening');base='http://127.0.0.1:'+server.address().port;
  t.after(()=>new Promise(resolve=>{server.closeAllConnections();server.close(resolve);}));
  const broker=createMcpOAuthBroker({testLoopback:true,configurations:[{...identity,serverId:'one',resource:base+'/mcp',issuer:base,protectedResourceMetadataUrl:base+'/resource-metadata',authorizationMetadataUrl:base+'/issuer-metadata',authorizationEndpoint:base+'/authorize',tokenEndpoint:base+'/token',redirectUri:base+'/callback',clientId:'registered-client',scopes:['read']}]});
  const begin=()=>broker.begin({identity,serverId:'one'});
  const callback=url=>base+'/callback?'+new URLSearchParams({state:new URL(url).searchParams.get('state'),code:'code-one',iss:base});
  return{broker,state,base,begin,callback};
}
test('PKCE S256 resource-bound grant, credential isolation, one shot, revoke',async t=>{
  const f=await fixture(t),{authorizationUrl}=await f.begin();const auth=new URL(authorizationUrl);
  assert.equal(auth.searchParams.get('code_challenge_method'),'S256');assert.equal(auth.searchParams.get('resource'),f.base+'/mcp');
  const done=await f.broker.complete({identity,serverId:'one',callbackUrl:f.callback(authorizationUrl)});assert.equal(done.connected,true);assert.doesNotMatch(JSON.stringify(done),/token/);
  assert.equal(f.state.tokenRequests.length,1);assert.equal(f.state.tokenRequests[0].get('resource'),f.base+'/mcp');assert.ok(f.state.tokenRequests[0].get('code_verifier'));
  const request={identity,serverId:'one',resource:f.base+'/mcp',issuer:f.base};assert.equal((await f.broker.credentialProvider(request)).accessToken,'test-only-token');
  await assert.rejects(f.broker.complete({identity,serverId:'one',callbackUrl:f.callback(authorizationUrl)}),/STATE/);
  await assert.rejects(f.broker.credentialProvider({...request,identity:{accountId:'b',projectId:'p'}}),/SCOPE/);
  f.broker.revoke({identity,serverId:'one'});assert.equal(await f.broker.credentialProvider(request),null);
});
test('metadata issuer mismatch rejected before authorization',async t=>{
  const f=await fixture(t);f.state.issuerMismatch=true;await assert.rejects(f.begin(),/ISSUER_MISMATCH/);assert.equal(f.state.tokenRequests.length,0);
});
test('callback issuer/state/endpoint mismatch never exchanges authorization code',async t=>{
  const f=await fixture(t),{authorizationUrl}=await f.begin(),url=new URL(f.callback(authorizationUrl));url.searchParams.set('iss','https://evil.example');
  await assert.rejects(f.broker.complete({identity,serverId:'one',callbackUrl:url.href}),/ISSUER/);
  url.searchParams.delete('iss');await assert.rejects(f.broker.complete({identity,serverId:'one',callbackUrl:url.href}),/ISSUER/);
  url.searchParams.set('iss',f.base);url.searchParams.set('state','bad');await assert.rejects(f.broker.complete({identity,serverId:'one',callbackUrl:url.href}),/STATE/);
  url.pathname='/other';await assert.rejects(f.broker.complete({identity,serverId:'one',callbackUrl:url.href}),/CALLBACK/);assert.equal(f.state.tokenRequests.length,0);
});
test('new grant invalidates old state and resource mismatched reads return no token',async t=>{
  const f=await fixture(t),one=await f.begin(),two=await f.begin();
  await assert.rejects(f.broker.complete({identity,serverId:'one',callbackUrl:f.callback(one.authorizationUrl)}),/STATE/);
  await f.broker.complete({identity,serverId:'one',callbackUrl:f.callback(two.authorizationUrl)});
  assert.equal(await f.broker.credentialProvider({identity,serverId:'one',resource:'https://evil.example',issuer:f.base}),null);
});

