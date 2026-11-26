import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import {runAgent} from '../src/agent.mjs';

test('real SDK resumes a lost model connection after a confirmed write', {timeout:60000},async t=>{
  const root=await fs.mkdtemp(path.join(os.tmpdir(),'sdk-transport-'));
  t.after(()=>fs.rm(root,{recursive:true,force:true}));
  const workspace=path.join(root,'work');
  await fs.mkdir(workspace);
  let calls=0, writes=0, receipts=0;
  const server=http.createServer(async(req,res)=>{
    let body='';
    for await (const chunk of req) body+=chunk;
    calls++;
    if(calls===2){req.socket.destroy();return;}
    if(calls===3 && body.includes('Wrote'))receipts++;
    const action=calls===1;
    const delta=action?{role:'assistant',tool_calls:[{index:0,id:'write_1',type:'function',
      function:{name:'write',arguments:JSON.stringify({path:'marker.txt',content:'one'})}}]}:{role:'assistant',content:'Done'};
    const chunk=(d,finish_reason)=>'data: '+JSON.stringify({id:'test',object:'chat.completion.chunk',model:'fixture',
      choices:[{index:0,delta:d,finish_reason}]})+'\n\n';
    res.writeHead(200,{'Content-Type':'text/event-stream'});
    res.end(chunk(delta,null)+chunk({},action?'tool_calls':'stop')+'data: [DONE]\n\n');
  });
  await new Promise((resolve,reject)=>{server.once('error',reject);server.listen(0,'127.0.0.1',resolve);});
  t.after(()=>{server.closeAllConnections();server.close();});
  await runAgent({workspace,sessionDir:path.join(root,'session'),prompt:'Create marker.txt',approvalMode:'allow',execution:{mode:'react',
    recovery:{allowUnknownCost:true},identity:{accountId:'owner',projectId:'project',sessionId:'session',taskId:'task',agentId:'agent'}}},
    {signal:AbortSignal.timeout(45000),onTools:()=>{},emit:e=>{if(e.type==='tool_end'&&e.toolName==='write'&&e.ok)writes++;}},
    {CODING_MODEL_MODEL:'fixture',CODING_MODEL_API_KEY:'fixture-transport-only',CODING_MODEL_ALLOW_PRIVATE:'true',
      CODING_MODEL_BASE_URL:'http://127.0.0.1:'+server.address().port+'/v1'});
  assert.equal(calls,3);
  assert.equal(writes,1);
  assert.equal(receipts,1);
  assert.equal(await fs.readFile(path.join(workspace,'marker.txt'),'utf8'),'one');
});
