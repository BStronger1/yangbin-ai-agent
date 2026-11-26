import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import {randomUUID,createHash} from 'node:crypto';
import {fork} from 'node:child_process';
import {once} from 'node:events';
import {fileURLToPath} from 'node:url';
import {nativeDirectory} from '../native-directory.mjs';
import {CandidateWorkspace} from '../candidate-workspace.mjs';

const name='orders.py',before='sum(rows[:-1])\n',after='sum(rows)\n',args={path:name,oldText:'rows[:-1]',newText:'rows'};
const permit=async()=>{},sha=value=>createHash('sha256').update(value).digest('hex');
async function fixture(t,fault) {
  const root=await fs.mkdtemp(path.join(os.tmpdir(),'yangbin-candidate-'));
  t.after(()=>fs.rm(root,{force:true,recursive:true}));
  const repo=path.join(root,'shared'),state=path.join(root,'private'),marker=path.join(root,'writes');
  await fs.mkdir(repo);await fs.writeFile(path.join(repo,name),before);await fs.writeFile(marker,'');
  const directory=await nativeDirectory(repo);
  const identity=['test-origin','owner','project',repo,'session','turn'];
  return {repo,state,marker,identity,open:(extra={})=>new CandidateWorkspace({directory,state,identity,fault,...extra})};
}
for(const [stage,phase,count] of [['prepared','PREPARED',0],['written','PREPARED',1],['confirmed','CONFIRMED',1]]) {
  test('process death at '+stage+' keeps effect and observed bytes separate',{timeout:20000},async t=>{
    const f=await fixture(t),id=randomUUID();
    const child=fork(fileURLToPath(new URL('./fixtures/candidate-writer.mjs',import.meta.url)),[f.repo,f.state,id,stage,f.marker],
      {execArgv:[],windowsHide:true,stdio:['ignore','ignore','pipe','ipc']});
    const exit=once(child,'exit');
    t.after(async()=>{if(child.exitCode===null && child.signalCode===null){child.kill('SIGKILL');await exit;}});
    const [event]=await Promise.race([once(child,'message'),exit.then(()=>{throw new Error('writer exited before fault injection');})]);
    assert.equal(event.phase,stage);
    const recovered=f.open(),alive=await recovered.reconcile(id);
    assert.equal(alive.writerAlive,true);
    await assert.rejects(recovered.propose('write',{path:'new.txt',content:'blocked'},randomUUID(),permit),/旧执行器/);
    child.kill('SIGKILL');await exit;
    const receipt=await recovered.reconcile(id);
    assert.equal(receipt.phase,phase);assert.equal(receipt.writerAlive,false);
    assert.equal(receipt.bytesMatch,count===1);assert.equal(receipt.baselineMatches,true);
    // Every retry reads the historic receipt. Once the model knows the bytes it
    // wanted, that knowledge alone cannot confirm an interrupted operation.
    assert.equal((await recovered.propose('edit',args,id,permit)).phase,phase);
    assert.equal((await fs.readFile(f.marker,'utf8')).length,2*count);
    assert.equal(await fs.readFile(path.join(f.repo,name),'utf8'),before);
    assert.deepEqual(await fs.readdir(f.repo),[name]);
    if(count)assert.equal((await recovered.staged())[0].after,after);
    const wrongOwner=f.open({identity:[...f.identity,'other-owner']});
    assert.equal((await wrongOwner.reconcile(id)).phase,'MISSING');
  });
}
test('shared editor wins the commit race; duplicate, changed arguments and expired execution are handled',async t=>{
  let writes=0,intervene=true;
  const f=await fixture(t),journal=f.open({fault:async stage=>{
    if(stage==='prepared'&&intervene){intervene=false;await fs.writeFile(path.join(f.repo,name),'edited in IDE\n');}
    if(stage==='written')writes++;
  }}),id=randomUUID();
  await journal.read({path:name});
  const first=await journal.propose('edit',args,id,permit);
  assert.equal(first.phase,'CONFIRMED');assert.equal(first.bytesMatch,true);
  assert.equal(first.baselineMatches,false);assert.equal(first.candidate.conflict,true);
  assert.equal(first.candidate.baseHash,sha(before));assert.equal(first.candidate.afterHash,sha(after));
  assert.equal(await fs.readFile(path.join(f.repo,name),'utf8'),'edited in IDE\n');
  assert.equal(await journal.read({path:name}),after);
  assert.deepEqual(await journal.propose('edit',args,id,permit),first);
  await assert.rejects(journal.propose('edit',{...args,newText:'other'},id,permit),/不同参数/);
  assert.equal(writes,1);
  const added=await journal.propose('write',{path:'nested/new.py',content:'candidate'},randomUUID(),permit);
  assert.equal(added.candidate.baseHash,null);
  assert.match(await journal.list('.'),/dir  nested/);assert.match(await journal.list('nested'),/file new.py/);
  await assert.rejects(fs.stat(path.join(f.repo,'nested')), {code:'ENOENT'});
  const directory=await nativeDirectory(f.repo),overlay=new Map((await journal.staged()).map(c=>[c.path,c.after]));
  assert.deepEqual(JSON.parse(await directory.search({query:'candidate'},overlay)).matches.map(m=>m.path),['nested/new.py']);
  assert.deepEqual(JSON.parse(await directory.search({query:'edited in IDE'},overlay)).matches,[]);
  assert.deepEqual(JSON.parse(await directory.search({query:'sum(rows)',path:name},overlay)).matches.map(m=>m.path),[name]);
  let calls=0;const expired=randomUUID();
  await assert.rejects(journal.propose('write',{path:'expired.py',content:'x'},expired,async()=>{if(++calls===3)throw new Error('expired');}),/expired/);
  assert.equal((await journal.reconcile(expired)).phase,'PREPARED');
  assert.equal((await journal.reconcile(expired)).bytesMatch,false);assert.equal(writes,2);
  await assert.rejects(journal.propose('write',{path:'.env',content:'x'},randomUUID(),permit),/受保护/);
  const denied=randomUUID();
  await assert.rejects(journal.propose('write',{path:'denied.py',content:'x'},denied,async()=>{throw new Error('denied');}),/denied/);
  assert.equal((await journal.reconcile(denied)).phase,'MISSING');
});
test('a page reconnecting to the same live desktop sees the outstanding writer',async t=>{
  const f=await fixture(t),id=randomUUID();
  let mark,release;
  const prepared=new Promise(resolve=>{mark=resolve}),barrier=new Promise(resolve=>{release=resolve});
  const journal=f.open({fault:async stage=>{if(stage==='prepared'){mark();await barrier;}}});
  const pending=journal.propose('write',{path:'draft.txt',content:'draft'},id,permit);
  await prepared;
  try {
    assert.equal((await f.open().reconcile(id)).writerAlive,true);
    await assert.rejects(f.open().propose('write',{path:'other.txt',content:'x'},randomUUID(),permit),/旧执行器/);
  }finally{release();}
  assert.equal((await pending).writerAlive,false);
});

test('candidate edit predicates fail before private state is created and allow correction',async t=>{
  const f=await fixture(t),journal=f.open();await journal.read({path:name});
  for(const oldText of ['missing','s']) {
    const invalid=randomUUID();
    await assert.rejects(journal.propose('edit',{...args,oldText},invalid,permit),error=>{
      assert.deepEqual(error.mutationReceipt,{effect:'NO_EFFECT',phase:'VALIDATING'});return true;
    });
    await assert.rejects(fs.stat(journal.root),{code:'ENOENT'});
    assert.equal((await journal.reconcile(invalid)).phase,'MISSING');
  }
  const result=await journal.propose('edit',args,randomUUID(),permit);
  assert.equal(result.phase,'CONFIRMED');assert.equal(result.candidate.after,after);
  assert.equal(await fs.readFile(path.join(f.repo,name),'utf8'),before);
});

for(const [stage,effect] of [['prepared','UNKNOWN'],['written','COMMITTED'],['confirmed','COMMITTED']]) {
  test('candidate error at '+stage+' preserves actual mutation effect',async t=>{
    const f=await fixture(t),journal=f.open({fault:async phase=>{if(phase===stage)throw new Error('injected');}}),id=randomUUID();
    await journal.read({path:name});
    await assert.rejects(journal.propose('edit',args,id,permit),error=>{
      assert.equal(error.mutationReceipt.effect,effect);return true;
    });
    const saved=await journal.reconcile(id);
    assert.equal(saved.bytesMatch,stage!=='prepared');
    assert.equal(saved.phase,stage==='confirmed'?'CONFIRMED':'PREPARED');
    if(stage!=='confirmed')await assert.rejects(journal.propose('write',{path:'other.txt',content:'blocked'},randomUUID(),permit),error=>{
      assert.equal(error.mutationReceipt.effect,'UNKNOWN');return true;
    });
    assert.equal(await fs.readFile(path.join(f.repo,name),'utf8'),before);
  });
}
