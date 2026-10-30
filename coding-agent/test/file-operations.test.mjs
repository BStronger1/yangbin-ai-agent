import test from 'node:test';import assert from 'node:assert/strict';
import fs from 'node:fs/promises';import os from 'node:os';import path from 'node:path';import {spawn} from 'node:child_process';
import {ToolRuntime} from '../src/tools.mjs';import {FileOperations} from '../src/file-operations.mjs';
const identity={accountId:'owner',projectId:'project',sessionId:'session',taskId:'turn'};
async function setup(){const root=await fs.mkdtemp(path.join(os.tmpdir(),'file-recovery-'));const workspace=path.join(root,'workspace'),journal=path.join(root,'journal');await fs.mkdir(workspace);await fs.writeFile(path.join(workspace,'main.txt'),'base');return{root,workspace,journal}}
for(const stage of ['prepared','staged','replaced','committed'])test(`real worker death at ${stage} preserves inspectable operation`,async()=>{
 const {root,workspace,journal}=await setup();try{
 const child=spawn(process.execPath,[new URL('./fixtures/file-crash.mjs',import.meta.url).pathname.replace(/^\/([A-Z]:)/i,'$1'),workspace,journal,Buffer.from(JSON.stringify(identity)).toString('base64'),stage],{stdio:'ignore'});
 const code=await new Promise((resolve,reject)=>{child.on('error',reject);child.on('exit',resolve)});assert.notEqual(code,0);
 const content=await fs.readFile(path.join(workspace,'main.txt'),'utf8');assert.equal(content,['replaced','committed'].includes(stage)?'base+once':'base');
 const files=(await fs.readdir(journal)).filter(n=>n.endsWith('.json'));assert.equal(files.length,1);const record=JSON.parse(await fs.readFile(path.join(journal,files[0])));
 assert.equal(record.state,stage==='committed'?'APPLIED':'PREPARED');assert.ok(record.beforeHash&&record.afterHash);assert.equal(record.callId,'crash-operation');
 const tools=new ToolRuntime({workspace,approvalMode:'allow',execution:{fileJournalDir:journal,identity}});await tools.init();
 await assert.rejects(()=>tools.execute('edit',{path:'main.txt',oldText:'base',newText:'base+once'},'crash-operation'));
 assert.equal(await fs.readFile(path.join(workspace,'main.txt'),'utf8'),content);
 }finally{await fs.rm(root,{recursive:true,force:true})}
});
test('same completed operation reuses receipt; different arguments cannot reuse identity',async()=>{
 const {root,workspace,journal}=await setup();try{const tools=new ToolRuntime({workspace,approvalMode:'allow',execution:{fileJournalDir:journal,identity}});await tools.init();
 const args={path:'main.txt',oldText:'base',newText:'base+once'};await tools.execute('edit',args,'same');await tools.execute('edit',args,'same');assert.equal(await fs.readFile(path.join(workspace,'main.txt'),'utf8'),'base+once');
 await assert.rejects(()=>tools.execute('edit',{...args,newText:'overwrite'},'same'),/different arguments/);
 }finally{await fs.rm(root,{recursive:true,force:true})}
});
test('external edit between preparation and replacement is preserved',async()=>{
 const {root,workspace,journal}=await setup();try{const fileJournal=new FileOperations({directory:journal,identity,checkpoint:async stage=>{if(stage==='staged')await fs.writeFile(path.join(workspace,'main.txt'),'external')}});
 const tools=new ToolRuntime({workspace,approvalMode:'allow',fileJournal});await tools.init();await assert.rejects(()=>tools.execute('edit',{path:'main.txt',oldText:'base',newText:'after'},'one'),/changed since/);assert.equal(await fs.readFile(path.join(workspace,'main.txt'),'utf8'),'external');
 }finally{await fs.rm(root,{recursive:true,force:true})}
});
test('journal persistence failure happens before any target write',async()=>{
 const {root,workspace,journal}=await setup();try{await fs.writeFile(journal,'not a directory');const tools=new ToolRuntime({workspace,approvalMode:'allow',execution:{fileJournalDir:journal,identity}});await tools.init();await assert.rejects(()=>tools.execute('edit',{path:'main.txt',oldText:'base',newText:'after'},'one'));assert.equal(await fs.readFile(path.join(workspace,'main.txt'),'utf8'),'base');
 }finally{await fs.rm(root,{recursive:true,force:true})}
});
