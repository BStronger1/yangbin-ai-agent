import { chromium } from 'playwright';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import assert from 'node:assert/strict';
import {createLocalRunner} from '../../coding-agent/src/local-runner.mjs';
const root=await fs.mkdtemp(path.join(os.tmpdir(),'yangbin-browser-runner-'));
const origin='http://localhost:19036', runner=await createLocalRunner({workspace:root,origin});
const browser=await chromium.launch({headless:true,executablePath:process.env.CHROME_EXECUTABLE_PATH||'C:/Program Files/Google/Chrome/Application/chrome.exe'});
try{
 await fs.writeFile(path.join(root,'index.html'),'<h1>真实本机预览</h1>');
 const page=await browser.newPage();await page.context().grantPermissions(['local-network-access'],{origin});page.on('console',m=>{if(m.type()==='error')console.error(m.text())});
 // Only the native directory chooser is substituted. Pairing proof, HTTP, CORS,
 // process start and preview all use the real helper and filesystem.
 await page.exposeFunction('writeProof',async(name,proof)=>{assert.match(name,/^\.yangbin-pair-[a-f0-9-]{36}$/);await fs.writeFile(path.join(root,name),proof,{flag:'wx'});});
 await page.exposeFunction('removeProof',async name=>{assert.match(name,/^\.yangbin-pair-[a-f0-9-]{36}$/);await fs.unlink(path.join(root,name));});
 await page.route(origin+'/**',async route=>route.fulfill({contentType:route.request().url().endsWith('.js')?'text/javascript':'text/html',body:route.request().url().endsWith('.js')?await fs.readFile(new URL('../src/local-runner.js',import.meta.url)):'<html><title>Runner acceptance</title></html>'}));
 await page.goto(origin);
 const result=await page.evaluate(async config=>{
  const {LocalRunner}=await import('/local-runner.js');
  const directory={ready:async()=>{},handle:{getFileHandle:async name=>({createWritable:async()=>({write:async proof=>window.writeProof(name,proof),close:async()=>{}})}),removeEntry:name=>window.removeProof(name)}};
  const client=await new LocalRunner(config.url,config.token).pair(directory);
  const preview=JSON.parse(await client.execute('preview',{path:'index.html'}));
  const statuses=JSON.parse(await client.execute('process_status',{}));
  await client.execute('stop',{id:preview.id});
  const stopped=JSON.parse(await client.execute('process_status',{id:preview.id}));
  let badUrl=false;try{new LocalRunner('http://evil.example:1234',config.token);}catch{badUrl=true;}
  return {paired:!!client.binding,running:statuses[0].state,stopped:stopped.state,url:preview.url,badUrl};
 },{url:runner.url,token:runner.token});
 assert.equal(result.paired,true);assert.equal(result.running,'RUNNING');assert.equal(result.stopped,'STOPPED');assert.equal(result.badUrl,true);
 assert.deepEqual((await fs.readdir(root)).sort(),['index.html']);console.log(JSON.stringify({checks:5,...result}));
}finally{await browser.close();await runner.close();await fs.rm(root,{recursive:true,force:true});}
