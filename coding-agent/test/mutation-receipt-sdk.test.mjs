import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import { runAgent } from '../src/agent.mjs';
import { nativeDirectory } from '../../desktop/native-directory.mjs';

for (const mode of ['on', 'off', 'lost-receipt']) {
  test(`real SDK local edit recovery: ${mode}`, {timeout:30000}, async t => {
    const root=await fs.mkdtemp(path.join(os.tmpdir(),'receipt-sdk-'));
    t.after(()=>fs.rm(root,{recursive:true,force:true}));
    const workspace=path.join(root,'work');await fs.mkdir(workspace);
    await fs.writeFile(path.join(workspace,'source.txt'),'before');
    const directory=await nativeDirectory(workspace);
    let calls=0,tools;const events=[],pending=[];
    const server=http.createServer(async(req,res)=>{
      for await (const chunk of req) {} // A loopback fixture; no external model.
      calls++;
      const action=calls===1?['read',{path:'source.txt'}]:calls===2?
        ['edit',{path:'source.txt',oldText:mode==='lost-receipt'?'before':'missing',newText:'after'}]:
        calls===3?['edit',{path:'source.txt',oldText:'before',newText:'after'}]:null;
      const delta=action?{role:'assistant',tool_calls:[{index:0,id:'call_'+calls,type:'function',function:{name:action[0],arguments:JSON.stringify(action[1])}}]}:{role:'assistant',content:'完成'};
      const chunk=(delta,finish_reason)=>'data: '+JSON.stringify({id:'fixture',object:'chat.completion.chunk',model:'fixture',choices:[{index:0,delta,finish_reason}]})+'\n\n';
      res.writeHead(200,{'Content-Type':'text/event-stream'});
      res.end(chunk(delta,null)+chunk({},action?'tool_calls':'stop')+'data: [DONE]\n\n');
    });
    await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
    t.after(()=>{server.closeAllConnections();server.close();});
    const run=runAgent({workspace,sessionDir:path.join(root,'session'),prompt:'把 source.txt 的 before 改成 after',approvalMode:'allow',execution:{mode:'react',localFiles:true}},
      {signal:AbortSignal.timeout(20000),onTools:value=>{tools=value;},emit:event=>{
        events.push(event);
        if(event.type!=='local_fs')return;
        pending.push((async()=>{
          try {
            const output=event.operation==='ping'?JSON.stringify({repoTools:true}):await directory.execute(event.operation,event.arguments);
            if(mode==='lost-receipt' && event.operation==='edit') {
              tools.localResult({requestId:event.requestId,ok:false,output:'Disconnected after dispatch'});
            } else tools.localResult({requestId:event.requestId,ok:true,output});
          } catch(error) {
            tools.localResult({requestId:event.requestId,ok:false,output:error.message,mutationReceipt:error.mutationReceipt});
          }
        })());
      }},
      {CODING_MODEL_MODEL:'fixture',CODING_MODEL_API_KEY:'local-fixture',CODING_MODEL_ALLOW_PRIVATE:'true',CODING_MODEL_BASE_URL:'http://127.0.0.1:'+server.address().port+'/v1',CODING_MUTATION_RECEIPTS_FIX:mode==='off'?'off':'on'});
    if(mode==='on')await run;
    else await assert.rejects(run);
    await Promise.all(pending);
    const settlements=events.filter(e=>e.type==='tool_settlement' && e.toolName==='edit');
    assert.equal(calls,mode==='on'?4:2);
    assert.equal(settlements[0].outcome,mode==='on'?'NO_EFFECT':'UNKNOWN_EFFECT');
    assert.equal(await fs.readFile(path.join(workspace,'source.txt'),'utf8'),mode==='off'?'before':'after');
    if(mode==='on')assert.equal(settlements[1].outcome,'CONFIRMED');
    const checkpoint=JSON.parse(await fs.readFile(path.join(root,'session','turn-checkpoint.json'),'utf8'));
    assert.deepEqual(checkpoint.operations.map(x=>x.status),mode==='on'?['no_effect','confirmed']:['unconfirmed']);
  });
}
