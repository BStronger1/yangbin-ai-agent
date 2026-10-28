import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
import { createLocalRunner } from '../src/local-runner.mjs';
import { modelBudget } from '../src/model-budget.mjs';

test('model output headroom follows model and account limits, never the former 8K clamp',()=>{
  assert.equal(modelBudget({CODING_MODEL_NAME:'deepseek-v4.1-flash-1017'}).maxOutputTokens,256000);
  const b=modelBudget({CODING_MODEL_NAME:'deepseek-flash',CODING_OUTPUT_CEILING:'65536'});
  assert.equal(b.contextWindow,1000000);assert.equal(b.maxOutputTokens,65536);assert.equal(b.reserveTokens,67584);
  assert.equal(modelBudget({CODING_OUTPUT_TOKENS:'64000',CODING_CONTEXT_WINDOW:'200000'}).maxOutputTokens,64000);
  assert.equal(modelBudget({CODING_OUTPUT_TOKENS:'64000',CODING_OUTPUT_CEILING:'4096'}).maxOutputTokens,4096);
  for(const value of ['NaN','0','-1','1.5','384001'])assert.throws(()=>modelBudget({CODING_OUTPUT_TOKENS:value}));
});

test('local runner requires exact origin, token and matching project; starts, observes, deduplicates and stops real processes',async t=>{
  const root=await fs.mkdtemp(path.join(os.tmpdir(),'yangbin-runner-'));
  const origin='https://www.yangbinai.homes', runner=await createLocalRunner({workspace:root,origin});
  t.after(async()=>{await runner.close();await fs.rm(root,{recursive:true,force:true});});
  const rpc=(body,headers={})=>fetch(runner.url+'/rpc',{method:'POST',headers:{Origin:origin,Authorization:'Bearer '+runner.token,'Content-Type':'application/json',...headers},body:JSON.stringify(body)});
  assert.equal((await rpc({}, {Origin:'https://evil.example'})).status,403);
  assert.equal((await rpc({}, {Authorization:'Bearer wrong'})).status,401);
  assert.equal((await rpc({operation:'start',requestId:randomUUID(),args:{command:'echo forbidden'}})).status,403);
  const challenge=randomUUID(), proof='a'.repeat(64);
  assert.equal((await rpc({operation:'pair',args:{challenge,proof}})).status,400);
  await fs.writeFile(path.join(root,'.yangbin-pair-'+challenge),proof);
  const paired=await (await rpc({operation:'pair',args:{challenge,proof}})).json();assert.ok(paired.binding);
  await fs.rm(path.join(root,'.yangbin-pair-'+challenge));
  const requestId=randomUUID(), request={operation:'start',args:{command:'echo runner-ok'},requestId,binding:paired.binding};
  const first=await (await rpc(request)).json(), repeated=await (await rpc(request)).json();assert.equal(first.output.id,repeated.output.id);
  let status;for(let n=0;n<40;n++){status=runner.manager.status(first.output.id);if(status.state!=='RUNNING')break;await new Promise(r=>setTimeout(r,100));}
  assert.equal(status.state,'EXITED');assert.equal(status.exitCode,0);assert.match(status.output,/runner-ok/);
  const slow=await runner.manager.start({command:process.platform==='win32'?'Start-Sleep -Seconds 60':'sleep 60'});assert.equal(slow.state,'RUNNING');
  assert.equal((await runner.manager.stop(slow.id)).state,'STOPPED');
  await fs.writeFile(path.join(root,'index.html'),'<html>game fixture</html>');await fs.writeFile(path.join(root,'.env'),'private');
  const preview=await runner.manager.preview();assert.equal((await fetch(preview.url)).status,200);
  assert.equal((await fetch(new URL('/.env',preview.url))).status,404);
  assert.equal((await fetch(preview.url,{headers:{Origin:'https://evil.example'}})).status,404);
  await assert.rejects(runner.manager.preview({path:'../secret.html'}));
  await runner.manager.stop(preview.id);await assert.rejects(fetch(preview.url));
});
