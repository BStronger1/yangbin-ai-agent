import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { executeSmokeV3,scoreComponents } from './smoke-v3.mjs';
const here=path.dirname(fileURLToPath(import.meta.url));
const task=JSON.parse(await fs.readFile(path.join(here,'smoke-v3-task.json')));
async function temporary(t){
 const root=await fs.mkdtemp(path.join(os.tmpdir(),'m029-v3-eval-'));
 t.after(async()=>{assert.equal(path.dirname(path.resolve(root)),path.resolve(os.tmpdir()));assert.ok(path.basename(root).startsWith('m029-v3-eval-'));await fs.rm(root,{recursive:true,force:true});});
 return root;
}
test('V3-01: exact 2048 cap, actual summary then answer, four-group context boundaries, source freeze and replay refusal',{timeout:60000},async t=>{
 const root=await temporary(t);
 const {results,summary,requestMetadata,offlineRequests}=await executeSmokeV3({mode:'offline',runDirectory:root});
 assert.equal(results.length,4);assert.equal(summary.budget.requests,8);assert.ok(summary.budget.reservedTokens<=256000);
 assert.ok(results.every(row=>row.status==='completed'&&row.requests===2&&row.compactions===1&&row.reservedTokens<=64000&&row.quality===null));
 assert.equal(requestMetadata.length,8);
 for(let i=0;i<4;i++){
  const row=results[i],pair=offlineRequests.slice(i*2,i*2+2),records=requestMetadata.slice(i*2,i*2+2);
  assert.equal(pair[0].summary,true);assert.equal(pair[1].summary,false);
  assert.ok(records.every(record=>record.actualMaxOutputTokens===2048&&record.finishReasons.includes('stop')&&record.httpStatus===200));
  assert.ok(records[0].visibleText.includes('Routine work complete'));assert.ok(records.every(record=>record.inputTokens>0&&record.outputTokens===20));
  assert.ok(pair.every(request=>(request.body.max_tokens??request.body.max_completion_tokens)===2048));
  const text=JSON.stringify(pair[1].body.messages),tools=pair[1].body.tools.map(tool=>tool.function.name);
  assert.equal(tools.includes('recall'),['B','D'].includes(row.group));assert.equal(tools.includes('bash'),false);
  assert.equal(text.includes('batchStride 必须为 43'),['B','D'].includes(row.group));
  assert.equal(text.includes('archiveCodec 统一为 zstd-framed'),['C','D'].includes(row.group));
  assert.ok(row.memoryEvents.every(event=>event.bytes<=1024&&event.count<=4));
 }
 const manifest=JSON.parse(await fs.readFile(path.join(root,'manifest.json')));
 assert.ok(manifest.sources['src/agent.mjs']);assert.ok(manifest.evaluatorHashes['smoke-v3.mjs']);
 await assert.rejects(executeSmokeV3({mode:'offline',runDirectory:root}),{code:'EEXIST'});
 t.diagnostic(JSON.stringify({externalRequests:0,loopbackRequests:8,realSdkCompactions:4,actualOutputCap:2048,reserved:summary.budget.reservedTokens}));
});
test('V3-02: empty answer halts remaining groups after two requests',{timeout:60000},async t=>{
 const root=await temporary(t);
 const {results,summary}=await executeSmokeV3({mode:'offline',runDirectory:root,testResponse:async(body,{summary})=>summary?'## Progress\nDone.\n## Next\nProceed.':''});
 assert.equal(results[0].status,'invalid_empty_response');
 assert.equal(results.filter(row=>row.status==='not_run_preflight_failure').length,3);
 assert.equal(summary.budget.requests,2);
});
test('V3-03: actual SDK rejects length-truncated summary; safe diagnostics distinguish thinking from visible text without recording hidden content',{timeout:60000},async t=>{
 const root=await temporary(t),hidden='HIDDEN_REASONING_CANARY';
 const {results,summary,requestMetadata}=await executeSmokeV3({mode:'offline',runDirectory:root,testResponse:async()=>({text:'Incomplete visible summary',thinking:hidden,finishReason:'length',usage:{prompt_tokens:100,completion_tokens:2048}})});
 assert.equal(summary.budget.requests,1);assert.equal(results[0].compactions,0);
 assert.equal(results[0].failureCategory,'summary_output_cap');
 assert.equal(results.filter(row=>row.status==='not_run_preflight_failure').length,3);
 assert.deepEqual(requestMetadata[0].finishReasons,['length']);assert.equal(requestMetadata[0].outputTokens,2048);assert.equal(requestMetadata[0].inputTokens,100);
 assert.equal(requestMetadata[0].thinkingChars,hidden.length);
 assert.equal(requestMetadata[0].visibleText,'Incomplete visible summary');
 assert.equal(JSON.stringify(requestMetadata).includes(hidden),false);
});
test('V3-04: component scoring distinguishes missing long-term evidence from general failure',()=>{
 const a=scoreComponents(task,'{"archiveCodec":null,"batchStride":43}','A');
 assert.equal(a.shortTermCorrect,true);assert.equal(a.longTermCorrect,false);assert.equal(a.jointCorrect,false);assert.equal(a.appropriateAbsence,true);assert.equal(a.longTermOutcome,'unavailable-abstained');
 const d=scoreComponents(task,'{"archiveCodec":"zstd-framed","batchStride":43}','D');
 assert.equal(d.shortTermCorrect,true);assert.equal(d.longTermCorrect,true);assert.equal(d.jointCorrect,true);assert.equal(d.appropriateAbsence,false);
 const stale=scoreComponents(task,'{"archiveCodec":"zstd-framed","batchStride":42}','D');
 assert.equal(stale.longTermCorrect,true);assert.equal(stale.shortTermCorrect,false);
 for(const bad of ['text','{"archiveCodec":null}','{"archiveCodec":null,"batchStride":43,"extra":true}']){
  const parsed=scoreComponents(task,bad,'A');assert.equal(parsed.responseFormatValid,false);assert.equal(parsed.jointCorrect,null);
 }
});
