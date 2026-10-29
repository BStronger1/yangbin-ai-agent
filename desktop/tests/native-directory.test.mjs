import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {nativeDirectory} from '../native-directory.mjs';
import {LocalProcessManager} from '../../coding-agent/src/local-runner.mjs';

test('native directory writes originals and rejects stale edits, escapes and links',async()=>{
 const root=await fs.mkdtemp(path.join(os.tmpdir(),'yangbin-desktop-'));
 try{
  await fs.writeFile(path.join(root,'index.html'),'<h1>before</h1>');
  const d=await nativeDirectory(root);
  await assert.rejects(d.write('index.html','overwrite'),/尚未读取/);
  assert.equal(await d.read('index.html'),'<h1>before</h1>');
  await d.execute('edit',{path:'index.html',oldText:'before',newText:'after'});
  assert.equal(await fs.readFile(path.join(root,'index.html'),'utf8'),'<h1>after</h1>');
  await fs.writeFile(path.join(root,'index.html'),'external');
  await assert.rejects(d.write('index.html','lost'),/外部修改/);
  await d.write('nested/game.js','console.log("ok")');
  assert.equal(await fs.readFile(path.join(root,'nested/game.js'),'utf8'),'console.log("ok")');
  for(const p of ['../outside','.env','.git/config','C:/windows/x'])await assert.rejects(d.read(p));
  await fs.link(path.join(root,'index.html'),path.join(root,'linked.html'));
  await assert.rejects(d.read('linked.html'),/Linked/);
  assert.deepEqual((await d.files()).map(f=>f.path),['nested/game.js']);
  await assert.rejects(d.write('cancel.txt','bad',{},()=>false),/任务已停止/);
  assert.equal(await fs.stat(path.join(root,'cancel.txt')).catch(()=>null),null);
  await assert.rejects(d.write('huge.txt','x'.repeat(65537)),/64KiB/);
  assert.ok((await fs.readdir(root)).every(n=>!n.startsWith('.yangbin-write')));
 }finally{await fs.rm(root,{recursive:true,force:true});}
});
test('built-in execution starts a real process and serves/stops a project preview',async()=>{
 const root=await fs.mkdtemp(path.join(os.tmpdir(),'yangbin-desktop-run-')),p=await new LocalProcessManager(root).init();
 try{
  await fs.writeFile(path.join(root,'index.html'),'<h1>desktop preview</h1>');
  const job=await p.start({command:process.platform==='win32'?"Write-Output 'desktop-ok'":"printf desktop-ok"});
  for(let n=0;n<100&&p.status(job.id).state==='RUNNING';n++)await new Promise(r=>setTimeout(r,50));
  assert.equal(p.status(job.id).exitCode,0);assert.match(p.status(job.id).output,/desktop-ok/);
  const preview=await p.preview({path:'index.html'});assert.match(await(await fetch(preview.url)).text(),/desktop preview/);
  await p.stop(preview.id);assert.equal(p.status(preview.id).state,'STOPPED');
 }finally{await p.close();await fs.rm(root,{recursive:true,force:true});}
});
