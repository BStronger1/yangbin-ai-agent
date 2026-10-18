import {chromium} from 'playwright';
import fs from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import assert from 'node:assert/strict';
const frontend=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..'),dist=path.join(frontend,'dist');
const catalog=JSON.parse(await fs.readFile(path.join(frontend,'../src/main/resources/work/catalog.json'),'utf8'));
const out=process.env.EVIDENCE_DIR||path.join(frontend,'tmp/mcp-ui');await fs.mkdir(out,{recursive:true});
const checks={},errors=[],unknown=[],requests=[],conversations=new Map(),files=[];
const profiles=[{id:'11111111-1111-4111-8111-111111111111',name:'我的 Claude',provider:'anthropic',model:'fixture-claude',baseUrl:'https://api.anthropic.com',hasKey:true},{id:'22222222-2222-4222-8222-222222222222',name:'我的 DeepSeek',provider:'deepseek',model:'fixture-deepseek',baseUrl:'https://api.deepseek.com',hasKey:true}];
const stamp='2026-10-18T14:00:00Z';let failModels=false;
const mcpTools=[
 {name:'yangbin_ai_agent_knowledge_currentTime',description:'获取当前时区时间',schema:JSON.stringify({type:'object',required:['timezone'],properties:{timezone:{type:'string',description:'IANA 时区'}}}),testable:true},
 {name:'yangbin_ai_agent_knowledge_listKnowledgeDocuments',description:'列出共享资料',schema:JSON.stringify({type:'object',properties:{}}),testable:true},
 {name:'yangbin_ai_agent_knowledge_readKnowledgeDocument',description:'读取共享资料',schema:JSON.stringify({type:'object',required:['filename'],properties:{filename:{type:'string'}}}),testable:true},
 {name:'external_searchImages',description:'未开放手动工具',schema:'{invalid',testable:false}
];const mcpHistory=[],mcpCalls=[];let lostResponse=false,discoveryError=false;
const browser=await chromium.launch({headless:true,args:['--disable-gpu'],...(process.env.CHROME_EXECUTABLE_PATH?{executablePath:process.env.CHROME_EXECUTABLE_PATH}:{})});
const page=await browser.newPage({viewport:{width:1440,height:1000}});page.on('pageerror',e=>errors.push(String(e)));
await page.route('**/*',async route=>{
 const req=route.request(),url=new URL(req.url()),name=url.pathname,method=req.method();let data={},status=200;
 if(name.startsWith('/api/')||name.startsWith('/ppt/api/')) {
  if(name==='/api/ai/mcp')data={status:discoveryError?'ERROR':'DISCOVERED',tools:discoveryError?[]:mcpTools,timeoutMs:30000,checkedAt:stamp,note:'工具发现不等于实时健康；当前为共享资料服务。'};
  else if(name==='/api/ai/mcp/history'&&method==='DELETE'){mcpHistory.splice(0);data={cleared:true};}
  else if(name==='/api/ai/mcp/history')data=mcpHistory;
  else if(name==='/api/ai/mcp/call'){
   const input=req.postDataJSON();mcpCalls.push(input);data=mcpHistory.find(r=>r.id===input.requestId);
   if(!data){data={id:input.requestId,name:input.name,arguments:input.arguments,status:'SUCCEEDED',success:true,result:JSON.stringify([{text:JSON.stringify(input.name.endsWith('currentTime')?'2026-10-19T12:00:00+08:00':'shared-notes.md')}]),durationMs:12,time:stamp,truncated:false};mcpHistory.unshift(data);}
   if(lostResponse){lostResponse=false;status=503;data={message:'response lost'}}
  }
  else if(name.startsWith('/api/ai/mcp/history/')){
   const id=name.split('/')[5];const entry=mcpHistory.find(r=>r.id===id);
   if(name.endsWith('/attach')){const partner=req.postDataJSON().partnerId;data={id:crypto.randomUUID(),partnerId:partner,conversationId:null,name:'MCP结果-'+id+'.md',mime:'text/plain',kind:'upload',createdAt:stamp};files.push(data)}else data=entry;
  }
  else if(name==='/api/auth/me')data={user:{id:'partner-fixture',username:'伙伴测试',role:'admin',csrf:'fixture'}};
  else if(name==='/api/ai/models'){if(failModels){status=503;data={message:'unavailable'}}else data={profiles,defaultModelId:profiles[0].id,providers:[{id:'anthropic',name:'Claude',defaultBaseUrl:'https://api.anthropic.com',suggestedModels:['fixture-claude']}]};}
  else if(name==='/api/ai/capabilities')data={rag:false,mcpTools:[],tools:[]};
  else if(name==='/api/ai/work/catalog')data=catalog;
  else if(name==='/api/ai/work/partners')data=catalog.partners.map(p=>p.id);
  else if(name==='/api/ai/work/library')data={skills:[],partners:catalog.partners.map(p=>p.id)};
  else if(name==='/api/ai/work/runs'||name==='/ppt/api/v1/projects')data=[];
  else if(name.startsWith('/api/ai/work/partners/')) {
   const bits=name.split('/'),partner=bits[5],operation=bits[6],id=bits[7];const p=catalog.partners.find(p=>p.id===partner);
   if(!p){status=404;data={message:'missing partner'}}
   else if(!operation)data={partner:p,skill:catalog.skills.find(s=>s.id===p.skillId),skills:catalog.skills.filter(s=>p.skillIds.includes(s.id)),policy:{enabled:true,mode:'ALLOW'},instructions:'伙伴指令',behavior:'能力边界'};
   else if(operation==='summon')data={partners:catalog.partners.map(p=>p.id),skills:[]};
   else if(operation==='profile')data={version:0,instructions:''};
   else if(operation==='resources')data=files.filter(r=>r.partnerId===partner);
   else if(operation==='files'){data={id:crypto.randomUUID(),partnerId:partner,conversationId:null,name:'材料.docx',mime:'application/vnd.openxmlformats-officedocument.wordprocessingml.document',kind:'upload',createdAt:stamp};files.push(data);}
   else if(operation==='conversations'&&!id)data=[...conversations.values()].filter(c=>c.partnerId===partner).map(c=>({...c,status:c.turns.at(-1)?.status}));
   else if(operation==='conversations'&&method==='DELETE'){requests.push({method,partner,id,revision:url.searchParams.get('revision')});conversations.delete(id);for(let i=files.length-1;i>=0;i--)if(files[i].conversationId===id)files.splice(i,1);data={deleted:true};}
   else if(operation==='conversations'){data=conversations.get(id);if(!data){status=400;data={message:'会话不存在'}}}
   else if(operation==='jobs'){
    const input=req.postDataJSON();requests.push({...input,partner});let c=conversations.get(input.conversationId)||{id:input.conversationId,partnerId:partner,title:input.text.slice(0,30),revision:0,turns:[],createdAt:stamp,updatedAt:stamp,nextBefore:null};
    const artifact={id:crypto.randomUUID(),partnerId:partner,conversationId:c.id,turnId:input.requestId,name:'交付文稿.docx',mime:'application/vnd.openxmlformats-officedocument.wordprocessingml.document',kind:'generated',createdAt:stamp};files.push(artifact);
    c.turns.push({id:input.requestId,text:input.text,answer:'# 已完成文稿\n\n'+('基于提供的材料整理，缺失信息待确认。\n\n').repeat(35),status:'SUCCEEDED',skillId:input.skillId,modelSnapshot:{...profiles.find(p=>p.id===input.modelProfileId)},artifacts:[artifact],attachments:files.filter(r=>input.attachmentIds.includes(r.id)),events:[{label:'生成可编辑文稿',at:stamp}],createdAt:stamp});c.revision++;c.total=c.turns.length;conversations.set(c.id,c);data=c;
   }else{unknown.push(method+' '+name);status=404;}
  }else{unknown.push(method+' '+name);status=404;}
  return route.fulfill({status,json:data});
 }
 let file=path.join(dist,name);try{if(!(await fs.stat(file)).isFile())throw Error()}catch{file=path.join(dist,'index.html')}
 return route.fulfill({body:await fs.readFile(file),contentType:({'.html':'text/html','.js':'text/javascript','.css':'text/css'})[path.extname(file)]||'application/octet-stream'});
});
async function shot(name){const cdp=await page.context().newCDPSession(page);const result=await cdp.send('Page.captureScreenshot',{format:'png'});await fs.writeFile(path.join(out,name),Buffer.from(result.data,'base64'));await cdp.detach();}
try {
 await page.goto('http://partners-fixture.test/mcp');await page.getByLabel('timezone',{exact:true}).waitFor();
 assert.equal(await page.getByLabel('timezone',{exact:true}).inputValue(),'Asia/Singapore');assert.match(await page.getByTestId('mcp-status').innerText(),/4/);checks.discoveredWithTypedForm=true;
 await page.getByLabel('timezone',{exact:true}).fill('');await page.getByRole('button',{name:'运行工具',exact:true}).click();assert.match(await page.getByRole('alert').innerText(),/必填/);assert.equal(mcpCalls.length,0);checks.requiredValidation=true;
 await page.getByLabel('timezone',{exact:true}).fill('UTC');await page.getByRole('button',{name:'运行工具',exact:true}).click();await page.getByTestId('mcp-result-status').waitFor();assert.equal(await page.getByTestId('mcp-result-status').innerText(),'SUCCEEDED');assert.match(await page.getByTestId('mcp-result').innerText(),/2026-10-19/);checks.actualResultRendered=true;
 assert.match(await page.getByRole('link',{name:'下载结果与来源'}).getAttribute('href'),/history\/.+\/download/);checks.downloadLink=true;
 await page.reload();await page.getByLabel('timezone',{exact:true}).waitFor();await page.locator('button.item').filter({hasText:'成功 ·'}).first().click();await page.getByTestId('mcp-result').waitFor();checks.historyRestored=true;
 await page.getByLabel('调用参数 JSON').fill('[');await page.getByRole('button',{name:'运行工具',exact:true}).click();assert.match(await page.getByRole('alert').innerText(),/合法/);assert.equal(mcpCalls.length,1);checks.jsonValidation=true;
 await page.locator('aside button.item').filter({hasText:'listKnowledgeDocuments'}).click();await page.getByRole('button',{name:'运行工具',exact:true}).click();await page.waitForFunction(()=>document.querySelector('[data-testid=mcp-result]')?.textContent.includes('shared-notes.md'));assert.deepEqual(mcpCalls.at(-1).arguments,{});assert.equal(await page.getByTestId('mcp-result').innerText(),'shared-notes.md');checks.noArgumentTool=true;checks.sdkTextArrayDecoded=true;
 await page.locator('aside button.item').filter({hasText:'external_searchImages'}).click();assert.ok(await page.getByRole('button',{name:'运行工具',exact:true}).isDisabled());checks.invalidSchemaAndUnexposedToolDisabled=true;
 await page.locator('aside button.item').filter({hasText:'currentTime'}).click();lostResponse=true;await page.getByRole('button',{name:'运行工具',exact:true}).click();await page.getByRole('button',{name:'确认同一调用'}).waitFor();const id=mcpCalls.at(-1).requestId;await page.getByRole('button',{name:'确认同一调用'}).click();await page.waitForFunction(()=>!document.querySelector('[aria-label=timezone]').disabled);assert.equal(mcpCalls.at(-1).requestId,id);assert.equal(mcpHistory.filter(r=>r.id===id).length,1);checks.uncertainRetryUsesSameId=true;
 await page.getByLabel('接收 MCP 结果的伙伴').selectOption('office');await page.getByRole('button',{name:'引用到伙伴',exact:true}).click();await page.locator('.composer-attachments').filter({hasText:'MCP结果'}).waitFor();assert.match(page.url(),/partners\/office\?resource=/);assert.match(await page.getByLabel('发给伙伴的消息').inputValue(),/MCP/);assert.equal(requests.length,0);checks.partnerAttachmentWithoutModelCall=true;
 await page.goto('http://partners-fixture.test/mcp');await page.getByLabel('timezone',{exact:true}).waitFor();await page.setViewportSize({width:390,height:844});await page.waitForFunction(()=>document.querySelector('.studio-sidebar').getBoundingClientRect().right<=1);assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));await shot('mcp-mobile.png');checks.mobileFits=true;
 await page.setViewportSize({width:1440,height:1000});await shot('mcp-desktop.png');page.once('dialog',d=>d.accept());await page.getByRole('button',{name:'清空历史'}).click();await page.getByText('还没有调用记录。').waitFor();assert.equal(mcpHistory.length,0);assert.equal(files.length,1);checks.clearHistoryKeepsPartnerResource=true;
 discoveryError=true;await page.getByRole('button',{name:'刷新',exact:true}).click();await page.getByText('服务状态未取得 · 0 项').waitFor();checks.discoveryFailureExplicit=true;
 discoveryError=false;await page.getByRole('button',{name:'刷新',exact:true}).click();await page.getByLabel('timezone',{exact:true}).waitFor();checks.discoveryRecovery=true;
 assert.deepEqual(errors,[]);assert.deepEqual(unknown,[]);checks.noPageErrors=true;
}finally{await fs.writeFile(path.join(out,'report.json'),JSON.stringify({checks,errors,unknown,modelCalls:requests.length},null,2));await browser.close()}
console.log(JSON.stringify(checks));
