import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { executeSmoke } from './smoke-v2.mjs';

async function temporary(t) {
  const root=await fs.mkdtemp(path.join(os.tmpdir(),'m029-v2-eval-'));
  t.after(async()=>{assert.equal(path.dirname(path.resolve(root)),path.resolve(os.tmpdir()));assert.ok(path.basename(root).startsWith('m029-v2-eval-'));await fs.rm(root,{recursive:true,force:true});});
  return root;
}
test('V2-01: all four groups actually compact with exact 512 output ceiling before answering', {timeout:60000}, async t=>{
  const root=await temporary(t);
  const {results,summary,requestMetadata,offlineRequests}=await executeSmoke({mode:'offline',runDirectory:root});
  assert.equal(results.length,4);
  assert.ok(results.every(row=>row.status==='completed'&&row.compactions===1&&row.requests===2&&row.quality===null));
  assert.equal(summary.budget.requests,8);assert.ok(summary.budget.reservedTokens<=256000);
  assert.ok(requestMetadata.every(row=>row.actualMaxOutputTokens===512));
  for(let i=0;i<results.length;i++){
    const row=results[i], pair=offlineRequests.slice(i*2,i*2+2);
    assert.equal(pair[0].summary,true);assert.equal(pair[1].summary,false);
    const final=pair[1].body,text=JSON.stringify(final.messages),tools=final.tools.map(x=>x.function.name);
    assert.equal(tools.includes('recall'),['B','D'].includes(row.group));
    assert.equal(tools.includes('bash'),false);
    assert.equal(text.includes('replayWindow 必须是 29'),['B','D'].includes(row.group));
    assert.equal(text.includes('wireFormat 固定为 cbor-seq'),['C','D'].includes(row.group));
    assert.ok(pair.every(request=>(request.body.max_tokens??request.body.max_completion_tokens)===512));
    assert.ok(row.memoryEvents.every(event=>event.bytes<=1024&&event.count<=4));
  }
  await assert.rejects(executeSmoke({mode:'offline',runDirectory:root}),{code:'EEXIST'});
  t.diagnostic(JSON.stringify({externalProviderRequests:0,loopbackRequests:8,compactions:4,actualMaxOutputTokens:512,reservedTokens:summary.budget.reservedTokens}));
});
test('V2-02: empty final answer stops remaining groups without replay', {timeout:60000}, async t=>{
  const root=await temporary(t);
  const {results,summary}=await executeSmoke({mode:'offline',runDirectory:root,testResponse:async(body,{summary})=>summary?'## Progress\nRoutine work done.\n## Next\nFollow the latest task.':''});
  assert.equal(results[0].status,'invalid_empty_response');
  assert.equal(results.filter(row=>row.status==='not_run_preflight_failure').length,3);
  assert.equal(summary.budget.requests,2);
  assert.ok(results.every(row=>row.quality===null));
});
