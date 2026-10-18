import {chromium} from 'playwright';
import fs from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import assert from 'node:assert/strict';
const frontend=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..'),dist=path.join(frontend,'dist');
const catalog=JSON.parse(await fs.readFile(path.join(frontend,'../src/main/resources/work/catalog.json'),'utf8'));
const out=process.env.EVIDENCE_DIR||path.join(frontend,'tmp/partners-ui');await fs.mkdir(out,{recursive:true});
const checks={},errors=[],unknown=[],requests=[],conversations=new Map(),files=[];
const profiles=[{id:'11111111-1111-4111-8111-111111111111',name:'我的 Claude',provider:'anthropic',model:'fixture-claude',baseUrl:'https://api.anthropic.com',hasKey:true},{id:'22222222-2222-4222-8222-222222222222',name:'我的 DeepSeek',provider:'deepseek',model:'fixture-deepseek',baseUrl:'https://api.deepseek.com',hasKey:true}];
const stamp='2026-10-18T14:00:00Z';let failModels=false;
const browser=await chromium.launch({headless:true,args:['--disable-gpu'],...(process.env.CHROME_EXECUTABLE_PATH?{executablePath:process.env.CHROME_EXECUTABLE_PATH}:{})});
const page=await browser.newPage({viewport:{width:1440,height:1000}});page.on('pageerror',e=>errors.push(String(e)));
await page.route('**/*',async route=>{
 const req=route.request(),url=new URL(req.url()),name=url.pathname,method=req.method();let data={},status=200;
 if(name.startsWith('/api/')||name.startsWith('/ppt/api/')) {
  if(name==='/api/auth/me')data={user:{id:'partner-fixture',username:'伙伴测试',role:'admin',csrf:'fixture'}};
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
 for(const partner of catalog.partners) {
  await page.goto('http://partners-fixture.test/partners/'+partner.id);
  await page.getByTestId('partner-model-select').waitFor();await page.waitForFunction(()=>!document.querySelector('[data-testid="partner-model-select"]').disabled);
  assert.equal(await page.locator('.partner-quick-actions>button').count(),partner.skillIds.length);
  assert.equal(await page.getByTestId('partner-model-select').inputValue(),profiles[0].id);
  assert.match(await page.locator('.skill-chip').innerText(),/自由对话/);
  await page.getByTestId('partner-model-select').selectOption(profiles[1].id);
  await page.locator('.partner-quick-actions>button').first().click();
  await page.getByLabel('发给伙伴的消息').fill('请按本轮格式整理资料，只给一段结论。');
  await page.getByRole('button',{name:'发送消息',exact:true}).click();await page.locator('.artifact-file').waitFor();
  const request=requests.at(-1);assert.equal(request.partner,partner.id);assert.equal(request.modelProfileId,profiles[1].id);assert.equal(request.skillId,partner.skillIds[0]);
  assert.match(await page.locator('.partner-turn-model').innerText(),/fixture-deepseek/);
  const box=await page.locator('.task-composer').boundingBox();assert.ok(box.y+box.height<=1000&&box.y>600);
  checks[partner.id+'-toolkit-model-delivery']=true;
 }
 await page.goto('http://partners-fixture.test/partners/office');await page.getByTestId('partner-model-select').waitFor();
 await page.waitForFunction(()=>!document.querySelector('[data-testid="partner-model-select"]').disabled);
 assert.equal(await page.getByTestId('partner-model-select').inputValue(),profiles[1].id);checks.modelChoiceRestored=true;
 await page.getByTestId('partner-model-settings').click();await page.getByTestId('model-manager').waitFor();assert.equal(await page.locator('.model-manager input[type=password]').inputValue(),'');await page.getByRole('button',{name:'关闭模型设置',exact:true}).click();checks.modelManagerAccessible=true;
 await shot('office-home.png');
 const office=[...conversations.values()].find(c=>c.partnerId==='office');await page.goto('http://partners-fixture.test/partners/office?conversation='+office.id);await page.locator('.artifact-file').waitFor();
 await page.getByLabel('添加附件或技能').click();await page.getByRole('button',{name:'引用任务资源',exact:true}).click();await page.getByRole('button',{name:'交付文稿.docx',exact:true}).click();assert.match(await page.locator('.composer-attachments').innerText(),/交付文稿/);checks.reuseGeneratedResource=true;
 await page.setViewportSize({width:390,height:844});await page.waitForTimeout(150);const mobile=await page.locator('.task-composer').boundingBox();assert.ok(mobile.x>=0&&mobile.x+mobile.width<=391&&mobile.y+mobile.height<=844);assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));await shot('office-mobile.png');checks.mobileComposer=true;
 await page.setViewportSize({width:1440,height:1000});page.once('dialog',dialog=>dialog.accept());await page.getByLabel('删除当前任务').click();await page.locator('.partner-quick-actions').waitFor();assert.ok(!conversations.has(office.id));checks.deleteWithRevision=requests.at(-1).revision==='1';
 await page.goto('http://partners-fixture.test/skills');await page.locator('.capability-card').first().waitFor();
 for(const s of catalog.skills){const link=page.locator('[data-id="'+s.id+'"] a').first();const target=await link.getAttribute('href');assert.equal(target,s.kind==='TRAVEL'?'/tasks?skill='+s.id:'/partners/'+s.partnerId+'?skill='+s.id)}checks.allSkillRoutes=true;
 for(const s of catalog.skills.filter(s=>s.kind==='TEXT')) {
  await page.goto('http://partners-fixture.test/partners/'+s.partnerId+'?skill='+s.id);await page.getByLabel('填写技能需求').waitFor();
  assert.equal(await page.locator('.skill-chip').innerText(),s.name);
  await page.getByLabel('填写技能需求').click();await page.getByRole('dialog').waitFor();
  assert.equal(await page.getByRole('dialog').locator('textarea').count(),s.inputs.length);
  await page.getByTestId('brief-field-0').fill('本轮用户目标');await page.getByTestId('brief-field-1').fill('仅使用附件中的真实材料');
  await page.getByRole('button',{name:'填入需求',exact:true}).click();assert.match(await page.getByLabel('发给伙伴的消息').inputValue(),/本轮用户目标/);
  checks['guided-'+s.id]=true;
 }
 await page.goto('http://partners-fixture.test/partners/education?skill=learning-quiz');await page.getByLabel('填写技能需求').waitFor();
 await page.getByLabel('发给伙伴的消息').fill('保留我的原始需求');await page.locator('.partner-quick-actions>button').last().click();assert.equal(await page.getByLabel('发给伙伴的消息').inputValue(),'保留我的原始需求');checks.skillChangePreservesDraft=true;
 await page.setViewportSize({width:390,height:844});await page.getByLabel('填写技能需求').click();await page.getByRole('dialog').waitFor();
 const dialogBox=await page.getByRole('dialog').boundingBox();assert.ok(dialogBox.x>=0&&dialogBox.x+dialogBox.width<=390&&dialogBox.y>=0&&dialogBox.y+dialogBox.height<=844);await shot('domain-intake-mobile.png');await page.keyboard.press('Escape');await page.getByRole('dialog').waitFor({state:'detached'});checks.guidanceMobileAndEscape=true;
 await page.setViewportSize({width:1440,height:1000});await page.goto('http://partners-fixture.test/partners/office?skill=study-plan');await page.getByRole('alert').waitFor();assert.match(await page.getByRole('alert').innerText(),/不属于当前伙伴/);assert.match(await page.locator('.skill-chip').innerText(),/自由对话/);checks.invalidSkillBinding=true;
 failModels=true;await page.goto('http://partners-fixture.test/partners/data');await page.getByRole('button',{name:'模型配置未能加载 · 重试'}).waitFor();await page.getByLabel('发给伙伴的消息').fill('任务');assert.ok(await page.getByRole('button',{name:'发送消息',exact:true}).isDisabled());failModels=false;await page.getByRole('button',{name:'模型配置未能加载 · 重试'}).click();await page.waitForFunction(()=>!document.querySelector('[aria-label="发送消息"]').disabled);checks.modelFailureRecovery=true;
 assert.deepEqual(errors,[]);assert.deepEqual(unknown,[]);assert.ok(Object.values(checks).every(Boolean));
} finally {await fs.writeFile(path.join(out,'report.json'),JSON.stringify({checks,errors,unknown},null,2));await browser.close();}
console.log(JSON.stringify(checks));
