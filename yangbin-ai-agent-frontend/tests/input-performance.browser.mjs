import {chromium} from 'playwright';
import fs from 'node:fs/promises';
import path from 'node:path';
import assert from 'node:assert/strict';
import {fileURLToPath} from 'node:url';
const frontend=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const dist=process.env.TEST_DIST||path.join(frontend,'dist'),out=process.env.EVIDENCE_DIR||path.join(frontend,'tmp/input-performance');
await fs.mkdir(out,{recursive:true});
const browser=await chromium.launch({headless:true,...(process.env.CHROME_EXECUTABLE_PATH?{executablePath:process.env.CHROME_EXECUTABLE_PATH}:{})});const results=[];
try{for(const [name,count,size] of [['typical',20,6000],['long',200,32000]]){
 const page=await browser.newPage({viewport:{width:1440,height:1000}});const errors=[];page.on('pageerror',e=>errors.push(String(e)));
 await page.addInitScript(()=>{
  window.perfStats={storage:0,serialization:0};const set=Storage.prototype.setItem,stringify=JSON.stringify;
  Storage.prototype.setItem=function(...args){window.perfStats.storage++;return set.apply(this,args)};
  JSON.stringify=function(value,...args){if(value?.__perfPayload)window.perfStats.serialization++;return stringify(value,...args)};
 });
 const session={id:'perf-session',projectId:'perf-project',title:'交互性能检查',status:'SUCCEEDED',approvalMode:'on-risk',revision:1,turns:[],events:[]};
 for(let t=0;t<count/10;t++)session.turns.push({id:'turn-'+t,prompt:'请检查项目 '+t,status:'SUCCEEDED'});
 for(let i=0;i<count;i++)session.events.push({seq:i,turnId:'turn-'+Math.floor(i/10),type:'tool_end',toolName:'read',ok:true,output:{__perfPayload:true,content:'代码内容 '.repeat(Math.ceil(size/5))}});
 for(const turn of session.turns)session.events.push({seq:session.events.length,turnId:turn.id,type:'text_delta',delta:'已完成本轮检查。'});
 await page.route('**/*',async route=>{
  const p=new URL(route.request().url()).pathname;
  if(p.startsWith('/api/')){
   let data={};
   if(p==='/api/auth/me')data={user:{id:'perf-owner',username:'fixture',role:'admin',csrf:'fixture'}};
   else if(p==='/api/ai/models')data={profiles:[],providers:[],systemEnabled:true,defaultModelId:'system'};
   else if(p.endsWith('/capabilities'))data={available:true,shellEnabled:false};
   else if(p==='/api/ai/coding/projects')data=[{id:'perf-project',name:'perf',source:'server'}];
   else if(p==='/api/ai/coding/sessions')data=[session];
   else if(p.endsWith('/perf-session'))data=session;
   else if(p.endsWith('/files')||p.endsWith('/diff'))data=[];
   else return route.fulfill({status:404,json:{}});
   return route.fulfill({json:data});
  }
  let file=path.join(dist,p);try{if(!(await fs.stat(file)).isFile())throw Error()}catch{file=path.join(dist,'index.html')}
  return route.fulfill({body:await fs.readFile(file),contentType:({'.html':'text/html','.js':'text/javascript','.css':'text/css'})[path.extname(file)]||'application/octet-stream'});
 });
 const cdp=await page.context().newCDPSession(page);await cdp.send('Emulation.setCPUThrottlingRate',{rate:4});
 await page.goto('http://localhost:19040/coding?session=perf-session');await page.locator('.coding-turn').last().waitFor();await page.getByTestId('coding-prompt').waitFor();
 const result=await page.evaluate(async()=>{
  const input=document.querySelector('[data-testid="coding-prompt"]');input.focus();window.perfStats={storage:0,serialization:0};
  const times=[];for(let i=0;i<30;i++){
   await new Promise(r=>requestAnimationFrame(r));const start=performance.now();input.value+='测';input.dispatchEvent(new Event('input',{bubbles:true}));
   await new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r)));times.push(performance.now()-start);
  }
  times.sort((a,b)=>a-b);return{p50:times[15],p95:times[28],max:times.at(-1),...window.perfStats,closedLogBodies:document.querySelectorAll('.coding-tool-event:not([open]) pre').length};
 });
 // Actual pointer interactions must remain available, and log contents stay inspectable.
 const start=Date.now();await page.getByTestId('approval-policy').click();await page.getByTestId('policy-allow').click();result.policyClickMs=Date.now()-start;
 await page.locator('.coding-tool-event summary').first().click();await page.locator('.coding-tool-event[open] pre').first().waitFor();
 assert.match(await page.locator('.coding-tool-event[open] pre').first().textContent(),/代码内容/);
 await page.reload();await page.locator('.coding-turn').last().waitFor();assert.equal(await page.getByTestId('coding-prompt').inputValue(),'测'.repeat(30));
 assert.deepEqual(errors,[]);
 if(process.env.EXPECT_OPTIMIZED==='1'){assert.equal(result.serialization,0);assert.equal(result.closedLogBodies,0);assert.ok(result.storage<15);}
 results.push({name,toolEvents:count,eventCharacters:size,cpuThrottle:4,...result});await page.close();
 }}finally{await browser.close()}
await fs.writeFile(path.join(out,'RESULT.json'),JSON.stringify({results,modelCalls:0,fixture:'synthetic completed conversation; input-to-two-animation-frames, not real-user latency'},null,2));console.log(JSON.stringify(results));
