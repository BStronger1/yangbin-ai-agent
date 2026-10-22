import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {createLocalExecutor} from '../src/teams/local-executor.mjs';
import {ToolRuntime} from '../src/tools.mjs';
import {BudgetLedger} from '../src/runtime/contracts.mjs';

async function review(t,content,read){
 const root=await fs.mkdtemp(path.join(os.tmpdir(),'yangbin-review-coverage-'));
 t.after(async()=>{if(path.dirname(root)===path.resolve(os.tmpdir())&&path.basename(root).startsWith('yangbin-review-coverage-'))await fs.rm(root,{recursive:true,force:true});});
 const workspace=path.join(root,'workspace'),sessionDir=path.join(root,'session'),file='inputs/evidence.txt';
 await fs.mkdir(path.join(workspace,'inputs'),{recursive:true});await fs.mkdir(sessionDir);await fs.writeFile(path.join(workspace,file),content);
 const readResults=[];
 const executor=createLocalExecutor({approvalMode:'allow',runAgent:async(input,hooks)=>{
  const tools=new ToolRuntime({...input,signal:hooks.signal,emit:()=>{}});await tools.init();hooks.onTools(tools);
  await read(async args=>{const result=await tools.execute('read',{path:file,...args});readResults.push(result);return result;});
  hooks.emit({type:'text_delta',delta:JSON.stringify({passed:true,findings:[]})});
 }});
 const result=await executor.execute({identity:{accountId:'fixture',projectId:'project',sessionId:'session',taskId:'review',agentId:'reviewer'},permissions:['read'],writePaths:[],role:'reviewer',prompt:'Review all evidence',feedback:'',inputs:[{path:file,name:'evidence.txt'}],workspace,sessionDir,signal:new AbortController().signal,ledger:new BudgetLedger({maxRequests:10,maxTokens:10000})});
 return{result,readResults,file};
}
test('reviewer empty EOF read is not evidence of inspecting the file',async t=>{
 const f=await review(t,'unseen content',read=>read({offset:1}));assert.equal(f.readResults[0].content[0].text,'');assert.deepEqual(f.result.readPaths,[]);
});
test('reviewer jumping directly to last line or nonzero character cannot claim complete coverage',async t=>{
 const line=await review(t,'unseen first line\nlast line',read=>read({offset:1}));assert.deepEqual(line.result.readPaths,[]);
 const character=await review(t,'unseen first character',read=>read({charOffset:1}));assert.deepEqual(character.result.readPaths,[]);
});
test('reviewer reading only first page of long artifact cannot claim complete coverage',async t=>{
 const f=await review(t,'a'.repeat(20000),read=>read({}));assert.notEqual(f.readResults[0].details.page.nextOffset,null);assert.deepEqual(f.result.readPaths,[]);
});
test('reviewer contiguous pagination from zero through final page counts as actual inspection',async t=>{
 const f=await review(t,'first line\n'+'a'.repeat(20000)+'\nlast line',async read=>{
  let offset=0,charOffset=0;for(let pages=0;pages<20;pages++){
   const result=await read({offset,charOffset,limit:2});const page=result.details.page;
   if(page.nextOffset===null)return;offset=page.nextOffset;charOffset=page.nextCharOffset;
  }throw Error('fixture pagination did not end');
 });assert.ok(f.readResults.length>2);assert.deepEqual(f.result.readPaths,[f.file]);
});
