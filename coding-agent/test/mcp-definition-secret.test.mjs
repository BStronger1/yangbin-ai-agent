import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { once } from 'node:events';
import { McpServer, createMcpHandler, fromJsonSchema } from '@modelcontextprotocol/server';
import { createMcpRuntime } from '../src/mcp/runtime.mjs';

// Independent review regression: synthetic credential is intentionally reflected by
// a reference SDK service into tool definitions. No external provider is contacted.
const syntheticToken='audit_token_1234567890_only_fixture';
const identity={accountId:'audit-account',projectId:'audit-project',sessionId:'s',taskId:'t',agentId:'g'};
for(const shape of ['enum','const','property-name','tool-name']) {
 test(`credential reflected in ${shape} is refused before any model definition or call`,async t=>{
  let calls=0,approvals=0,reservations=0;const events=[];
  const property=shape==='enum'?{type:'string',enum:[syntheticToken]}:shape==='const'?{type:'string',const:syntheticToken}:{type:'string'};
  const schema={type:'object',properties:{[shape==='property-name'?syntheticToken:'value']:property},additionalProperties:false};
  const handler=createMcpHandler(()=>{const server=new McpServer({name:'independent-credential-reflection-audit',version:'1.0.0'});
   server.registerTool(shape==='tool-name'?syntheticToken:'echo',{inputSchema:fromJsonSchema(schema)},async()=>{calls++;return {content:[]};});return server;});
  const server=http.createServer(async(req,res)=>{
   try{const chunks=[];for await(const chunk of req)chunks.push(chunk);const body=Buffer.concat(chunks);
    assert.equal(req.headers.authorization,'Bearer '+syntheticToken);
    const response=await handler.fetch(new Request(`http://127.0.0.1:${server.address().port}${req.url}`,{method:req.method,headers:req.headers,...(body.length?{body}:{})}));
    res.writeHead(response.status,Object.fromEntries(response.headers));if(response.body)for await(const chunk of response.body)res.write(chunk);res.end();
   }catch{res.writeHead(500);res.end();}
  });
  server.listen(0,'127.0.0.1');await once(server,'listening');t.after(()=>new Promise(resolve=>{server.closeAllConnections();server.close(resolve);}));
  const url=`http://127.0.0.1:${server.address().port}/mcp`;
  const runtime=createMcpRuntime({identity,servers:[{id:'audit',accountId:identity.accountId,projectId:identity.projectId,url,auth:{resource:url,issuer:'https://fixture-issuer.example'}}],testLoopback:true,
   authorize:async()=>{approvals++;return {approved:true};},reserve:async()=>{reservations++;return 'audit';},settle:async()=>{},emit:e=>events.push(e),
   credentialProvider:async()=>({accountId:identity.accountId,projectId:identity.projectId,resource:url,issuer:'https://fixture-issuer.example',scopes:[],expiresAt:Date.now()+60000,accessToken:syntheticToken})});
  t.after(()=>runtime.close());
  await assert.rejects(runtime.discover(),/MCP_CREDENTIAL_IN_DEFINITION/);
  assert.deepEqual(runtime.definitions(),[]);assert.equal(calls,0);assert.equal(approvals,0);assert.equal(reservations,0);assert.ok(!JSON.stringify(events).includes(syntheticToken));
 });
}
