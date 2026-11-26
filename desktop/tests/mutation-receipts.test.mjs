import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {nativeDirectory} from '../native-directory.mjs';
import {LocalDirectory} from '../../yangbin-ai-agent-frontend/src/local-directory.js';

async function fixture(t, source) {
  const root=await fs.mkdtemp(path.join(os.tmpdir(),'mutation-receipts-'));
  t.after(()=>fs.rm(root,{recursive:true,force:true}));
  await fs.writeFile(path.join(root,'source.py'),source);
  return {root,directory:await nativeDirectory(root)};
}
const receipt = (effect,phase) => error => {
  assert.deepEqual(error.mutationReceipt,{effect,phase}); return true;
};

for (const large of [false,true]) test(`native ${large?'paged large':'small'} edit predicate failures are definitely unwritten`,async t=>{
  const source=(large?'# padding\n'.repeat(8000):'')+'repeated repeated\nunique\n';
  const {root,directory}=await fixture(t,source);
  if(large)await directory.execute('read',{path:'source.py',paged:true});
  else await directory.read('source.py');
  for(const oldText of ['absent','repeated']) {
    await assert.rejects(directory.execute('edit',{path:'source.py',oldText,newText:'changed'}),receipt('NO_EFFECT','VALIDATING'));
    assert.equal(await fs.readFile(path.join(root,'source.py'),'utf8'),source);
    assert.deepEqual(await fs.readdir(root),['source.py']);
  }
  assert.match(await directory.execute('edit',{path:'source.py',oldText:'unique',newText:'corrected'}),/^Edited/);
  assert.equal(await fs.readFile(path.join(root,'source.py'),'utf8'),source.replace('unique','corrected'));
});

test('native staged and completed edits retain different error receipts',async t=>{
  const source='# padding\n'.repeat(8000)+'unique\n';
  const {root,directory}=await fixture(t,source);
  await directory.execute('read',{path:'source.py',paged:true});
  await assert.rejects(directory.execute('edit',{path:'source.py',oldText:'unique',newText:'changed'},()=>false),receipt('UNKNOWN','PREPARING'));
  assert.equal(await fs.readFile(path.join(root,'source.py'),'utf8'),source);
  directory.changes.set=()=>{throw new Error('receipt display failed');};
  await assert.rejects(directory.execute('edit',{path:'source.py',oldText:'unique',newText:'changed'}),receipt('COMMITTED','COMMITTED'));
  assert.equal(await fs.readFile(path.join(root,'source.py'),'utf8'),source.replace('unique','changed'));
});

function browserFixture({onOpen=async()=>{},onClose=async()=>{}}={}) {
  let value='repeated repeated\nunique\n';
  const handle={name:'source.py',kind:'file',async getFile(){const bytes=new TextEncoder().encode(value);return {size:bytes.length,arrayBuffer:async()=>bytes.buffer};},
    async createWritable(){await onOpen();let staged=value;return {
      async write(text){staged=text;},async truncate(){},async abort(){},
      async close(){value=staged;await onClose();}
    };}};
  const directory=new LocalDirectory({name:'project',async queryPermission(){return 'granted';},async getFileHandle(){return handle;}});
  return {directory,value:()=>value};
}

test('browser write phases preserve unknown close outcomes and confirmed bytes',async()=>{
  const f=browserFixture({onClose:async()=>{throw new Error('lost close response');}});
  await f.directory.read('source.py');
  await assert.rejects(f.directory.execute('edit',{path:'source.py',oldText:'absent',newText:'x'}),receipt('NO_EFFECT','VALIDATING'));
  await assert.rejects(f.directory.execute('edit',{path:'source.py',oldText:'unique',newText:'changed'}),receipt('UNKNOWN','COMMITTING'));
  assert.match(f.value(),/changed/);
  const committed=browserFixture();await committed.directory.read('source.py');
  committed.directory.changes.set=()=>{throw new Error('history unavailable');};
  await assert.rejects(committed.directory.write('source.py','committed'),receipt('COMMITTED','COMMITTED'));
  assert.equal(committed.value(),'committed');
});

test('concurrent browser operations keep independent receipt states',async()=>{
  let started,release;
  const opening=new Promise(resolve=>{started=resolve;}),barrier=new Promise(resolve=>{release=resolve;});
  const f=browserFixture({onOpen:async()=>{started();await barrier;throw new Error('open interrupted');}});
  await f.directory.read('source.py');
  const pending=assert.rejects(f.directory.write('source.py','new'),receipt('UNKNOWN','PREPARING'));
  await opening;
  await assert.rejects(f.directory.execute('edit',{path:'source.py',oldText:'absent',newText:'x'}),receipt('NO_EFFECT','VALIDATING'));
  release();await pending;assert.match(f.value(),/unique/);
});

test('creating a new browser file before open failure is an unknown effect',async()=>{
  let created=false;
  const directory=new LocalDirectory({name:'project',async queryPermission(){return 'granted';},async getFileHandle(name,{create=false}={}){
    if(!created && !create)throw Object.assign(new Error('missing'),{name:'NotFoundError'});
    created=true;return {async createWritable(){throw new Error('cannot open');}};
  }});
  await assert.rejects(directory.write('new.txt','candidate'),receipt('UNKNOWN','PREPARING'));
  assert.equal(created,true);
});

for(const large of [false,true])test(`native ${large?'large':'small'} rename survives a failed cleanup receipt`,async t=>{
  const source=(large?'# padding\n'.repeat(8000):'')+'unique\n';
  const {root,directory}=await fixture(t,source);
  if(large)await directory.execute('read',{path:'source.py',paged:true});else await directory.read('source.py');
  const remove=fs.rm;
  t.mock.method(fs,'rm',async(file,options)=>{
    if(path.basename(file).startsWith('.yangbin-write-'))throw new Error('cleanup unavailable');
    return remove(file,options);
  });
  await assert.rejects(directory.execute('edit',{path:'source.py',oldText:'unique',newText:'changed'}),receipt('COMMITTED','COMMITTED'));
  assert.equal(await fs.readFile(path.join(root,'source.py'),'utf8'),source.replace('unique','changed'));
});
