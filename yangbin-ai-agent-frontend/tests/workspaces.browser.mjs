import {chromium} from 'playwright';
import fs from 'node:fs/promises';
import path from 'node:path';
import assert from 'node:assert/strict';
import {fileURLToPath} from 'node:url';
// All API calls are intercepted: this is UI contract verification, not backend authorization proof.
const frontend=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..'),dist=path.join(frontend,'dist');
const out=path.resolve(process.env.EVIDENCE_DIR||path.join(frontend,'tmp/workspaces-m026'));await fs.mkdir(out,{recursive:true});
const checks={},errors=[],unknown=[],calls=[],conversations=new Map(),resources=[];
const stamp='2026-10-21T10:00:00Z',id=()=>crypto.randomUUID();
const profiles=[{id:id(),name:'聊天模型',model:'fixture-chat',provider:'openai',hasKey:true,reasoningEfforts:['low','high']},{id:id(),name:'工作模型',model:'fixture-work',provider:'openai',hasKey:true,reasoningEfforts:['low','high']}];
const catalog=JSON.parse(await fs.readFile(path.join(frontend,'../src/main/resources/work/catalog.json'),'utf8'));
let owner='owner-a',hold=false,failPages=false,denyJobs=false,delayNext=0;
const project={id:id(),name:'既有代码项目'},sid=id(),rid=id(),approval=id();
const coding={id:sid,projectId:project.id,title:'旧编程任务',status:'WAITING_APPROVAL',revision:1,approvalMode:'ask',turns:[{id:rid,prompt:'创建审阅文件',status:'WAITING_APPROVAL'}],events:[{seq:1,turnId:rid,type:'approval_required',approvalId:approval,toolName:'write',arguments:{path:'review.txt',content:'verified\n'}}]};
let wrote=false;
const key=cid=>owner+':'+cid;
function complete(c){const t=c.turns.at(-1);t.status='SUCCEEDED';t.answer=c.mode==='chat'?'模拟简短回答':'# 合成工作清单\n\n1. 整理材料\n2. 核验数据\n3. 交付文稿';t.events.push({label:'已保存回复',at:stamp});if(c.mode==='work'){const r={id:id(),partnerId:c.partnerId,conversationId:c.id,name:'成果文稿.docx',mime:'application/vnd.openxmlformats-officedocument.wordprocessingml.document',kind:'generated',createdAt:stamp};resources.push(r);t.artifacts=[r];}return c;}
const browser=await chromium.launch({headless:true,...(process.env.CHROME_EXECUTABLE_PATH?{executablePath:process.env.CHROME_EXECUTABLE_PATH}:{})});
const page=await browser.newPage({viewport:{width:1440,height:1000}});page.setDefaultTimeout(10000);page.on('pageerror',e=>errors.push(String(e)));page.on('console',message=>{if(message.type()==='error'&&message.text().includes('TypeError'))errors.push(message.text());});
await page.route('**/*',async route=>{
 const req=route.request(),u=new URL(req.url()),p=u.pathname,m=req.method();let data={},status=200;
 if(p.startsWith('/api/')){
  if(p==='/api/auth/me')data={user:{id:owner,username:owner,role:'user',csrf:'fixture'}};
  else if(p==='/api/ai/models')data={profiles,defaultModelId:'system',providers:[]};
  else if(p==='/api/ai/work/catalog')data=catalog;
  else if(p==='/api/ai/work/library')data={skills:[],partners:catalog.partners.map(x=>x.id)};
  else if(p==='/api/ai/work/partners')data=catalog.partners.map(x=>x.id);
  else if(p==='/api/ai/coding/capabilities')data={available:true,shellEnabled:false,model:'fixture'};
  else if(p==='/api/ai/coding/projects')data=[project];
  else if(p==='/api/ai/coding/sessions')data=owner==='owner-a'?[coding]:[];
  else if(p===`/api/ai/coding/projects/${project.id}/files`)data=wrote?[{path:'review.txt',size:9}]:[];
  else if(p===`/api/ai/coding/projects/${project.id}/file`)data={path:'review.txt',content:'verified\n'};
  else if(p.startsWith('/api/ai/coding/sessions/')){
   const operation=p.split('/')[6];if(owner!=='owner-a'){status=404;data={message:'会话不存在'}}
   else if(operation==='approvals'){calls.push({kind:'approval'});wrote=true;coding.status='SUCCEEDED';coding.turns[0].status='SUCCEEDED';coding.events.push({seq:2,turnId:rid,type:'approval_resolved',approvalId:approval},{seq:3,turnId:rid,type:'text_delta',delta:'文件已保存'});data=coding;}
   else if(operation==='diff')data=wrote?[{path:'review.txt',kind:'added',before:null,after:'verified\n'}]:[];
   else if(operation==='cancel'){calls.push({kind:'coding-cancel'});coding.status='CANCELLED';data=coding;}
   else data=coding;
  }else if(p.startsWith('/api/ai/assistant/partners/')){
   const parts=p.split('/'),partner=parts[5],op=parts[6],cid=parts[7],mode=u.searchParams.get('mode');
   if(op==='jobs'){
    const input=req.postDataJSON();calls.push({kind:'job',owner,partner,...input});if(delayNext){const wait=delayNext;delayNext=0;await new Promise(resolve=>setTimeout(resolve,wait));}
    if(denyJobs){status=429;data={message:'共享账号额度不足'};}
    else{assert.equal(mode,input.mode);const executionMode=input.mode;assert.ok(['chat','work'].includes(executionMode));if(executionMode==='work'){assert.ok(input.confirmed&&input.brief.goal&&input.brief.deliverable);if(input.modelProfileId===profiles[1].id){assert.equal(input.reasoningEffort,'low');checks.workEffortRequest=true;}}
     const c={id:input.conversationId,owner,partnerId:partner,mode:executionMode,title:input.text.slice(0,30),revision:1,total:1,nextBefore:null,turns:[{id:input.requestId,text:input.text,status:'RUNNING',answer:null,modelSnapshot:profiles.find(x=>x.id===input.modelProfileId),brief:input.brief,events:[{label:'正在整理材料',at:stamp}],attachments:[],artifacts:[]}]};conversations.set(key(c.id),c);if(!hold)complete(c);data=c;status=202;}
   }else if(op==='conversations'&&!cid)data=[...conversations.values()].filter(c=>c.owner===owner&&c.mode===mode&&c.partnerId===partner).map(c=>({...c,status:c.turns.at(-1).status}));
   else if(op==='conversations'){
    const c=conversations.get(key(cid));
    if(!c||c.mode!==mode||c.partnerId!==partner){status=404;data={message:'会话不存在'};}
    else if(parts[8]==='stop'){calls.push({kind:'cancel',cid});c.turns.at(-1).status='CANCELLED';data=c;}
    else if(parts[8]==='events'){
     if(c.turns.at(-1).status==='RUNNING'||failPages)return route.abort('failed');
     return route.fulfill({contentType:'text/event-stream',body:'event: state\ndata: '+JSON.stringify(c)+'\n\nevent: done\ndata: complete\n\n'});
    }else if(failPages){status=503;data={message:'模拟断线，请稍后恢复'};}
    else data=c;
   }else{unknown.push(m+' '+p);status=404;}
  }else if(p.startsWith('/api/ai/work/partners/')){
   const parts=p.split('/'),partner=parts[5],op=parts[6];const item=catalog.partners.find(x=>x.id===partner);
   if(!op)data={partner:item,skills:catalog.skills.filter(x=>item?.skillIds.includes(x.id)),policy:{enabled:true,mode:'ALLOW'}};
   else if(op==='resources')data=resources.filter(x=>x.partnerId===partner);
   else if(op==='profile')data={version:0,instructions:''};
   else{unknown.push(m+' '+p);status=404;}
  }else if(p==='/api/ai/manus/conversations')data=[];
  else{unknown.push(m+' '+p);status=404;data={message:'未声明夹具接口'};}
  return route.fulfill({status,json:data});
 }
 let file=path.join(dist,p);try{if(!(await fs.stat(file)).isFile())throw Error();}catch{file=path.join(dist,'index.html');}
 return route.fulfill({body:await fs.readFile(file),contentType:({'.html':'text/html','.css':'text/css','.js':'text/javascript'})[path.extname(file)]||'application/octet-stream'});
});
const go=async suffix=>{await page.goto('http://workspaces-fixture.test'+suffix);};
const jobCount=()=>calls.filter(x=>x.kind==='job').length;
const cancelCount=()=>calls.filter(x=>x.kind.includes('cancel')).length;
const assistant=async(mode)=>{await go('/assistant?mode='+mode);await page.getByTestId('assistant-prompt').waitFor();};
try{
 await assistant('chat');assert.equal(await page.getByTestId('assistant-prompt').inputValue(),'');checks.chatEmptyState=true;
 await page.getByTestId('partner-model-select').selectOption(profiles[0].id);await page.getByTestId('reasoning-effort').selectOption('high');await page.getByTestId('assistant-prompt').fill('聊天草稿');
 await page.getByTestId('assistant-mode-work').click();await page.getByTestId('assistant-prompt').waitFor();assert.equal(await page.getByTestId('assistant-prompt').inputValue(),'');
 await page.getByTestId('partner-model-select').selectOption(profiles[1].id);await page.getByTestId('reasoning-effort').selectOption('low');await page.getByTestId('assistant-prompt').fill('工作草稿');
 await page.getByTestId('assistant-mode-chat').click();assert.equal(await page.getByTestId('assistant-prompt').inputValue(),'聊天草稿');assert.equal(await page.getByTestId('partner-model-select').inputValue(),profiles[0].id);checks.modeDraftAndModelIsolation=true;
 await page.reload();await page.getByTestId('assistant-prompt').waitFor();assert.equal(await page.getByTestId('assistant-prompt').inputValue(),'聊天草稿');checks.draftRefresh=true;
 await page.getByTestId('assistant-prompt').fill('简单回答');await page.getByTestId('assistant-send').click();await page.getByText('模拟简短回答',{exact:true}).waitFor();assert.equal(jobCount(),1);assert.equal(calls[0].mode,'chat');assert.equal(calls[0].reasoningEffort,'high');checks.chatEffortRequest=true;checks.chatDirectNoConfirmation=true;
 const chatUrl=page.url();await page.reload();await page.getByText('模拟简短回答',{exact:true}).waitFor();assert.equal(jobCount(),1);checks.chatRefreshNoResubmit=true;
 await page.getByTestId('assistant-history').click();await page.getByText('简单回答',{exact:true}).last().waitFor();checks.chatHistory=true;
 await assistant('work');assert.equal(await page.getByTestId('assistant-prompt').inputValue(),'工作草稿');assert.equal(await page.getByTestId('partner-model-select').inputValue(),profiles[1].id);
 await page.getByTestId('assistant-prompt').fill('整理合成任务');await page.getByTestId('assistant-send').click();await page.getByRole('alert').filter({hasText:'确认本轮执行范围'}).waitFor();assert.equal(jobCount(),1);checks.workBlocksUnconfirmed=true;
 await page.getByTestId('assistant-goal').fill('整理提供的合成材料');await page.getByTestId('assistant-deliverable').fill('一份可审阅文稿');await page.getByTestId('assistant-constraints').fill('不虚构证据');await page.getByTestId('assistant-confirmed').check();hold=true;
 await page.getByTestId('assistant-send').click();await page.getByTestId('assistant-cancel').waitFor();assert.equal(jobCount(),2);const workUrl=page.url(),workId=new URL(workUrl).searchParams.get('conversation');assert.ok(workId);checks.workExplicitContract=true;
 await page.getByTestId('workspace-coding').click();await page.getByTestId('coding-workspace').waitFor();assert.equal(cancelCount(),0);checks.switchKeepsTask=true;
 await go(new URL(workUrl).pathname+new URL(workUrl).search);await page.getByTestId('assistant-cancel').waitFor();assert.equal(jobCount(),2);checks.runningStateRestored=true;
 failPages=true;await page.reload();await page.getByRole('alert').waitFor();assert.equal(jobCount(),2);checks.disconnectDoesNotResubmit=true;failPages=false;hold=false;complete(conversations.get(key(workId)));
 await page.reload();await page.getByText('成果文稿.docx',{exact:true}).first().waitFor();assert.equal(jobCount(),2);checks.workArtifactRecovery=true;
 await page.setViewportSize({width:390,height:844});await page.waitForFunction(()=>document.querySelector('.studio-sidebar')?.getBoundingClientRect().right<=0);
 const resultPanel=page.getByRole('complementary',{name:'成果面板'});await resultPanel.waitFor();assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));checks.mobileContentSidebarSettled=true;
 await page.screenshot({path:path.join(out,'work-mobile-results.png'),animations:'disabled'});
 await page.getByRole('button',{name:'关闭成果面板',exact:true}).click();await resultPanel.waitFor({state:'hidden'});const contentPrompt=page.getByTestId('assistant-prompt');assert.ok(await contentPrompt.isVisible());const contentBounds=await contentPrompt.boundingBox();assert.ok(contentBounds.x>=0&&contentBounds.y>=0&&contentBounds.x+contentBounds.width<=390&&contentBounds.y+contentBounds.height<=844);checks.mobileContentComposerAfterClosingResults=true;
 await page.screenshot({path:path.join(out,'work-mobile-main.png'),animations:'disabled'});await page.setViewportSize({width:1440,height:1000});await page.reload();await page.getByText('成果文稿.docx',{exact:true}).first().waitFor();assert.equal(await page.getByRole('complementary',{name:'成果面板'}).count(),0);checks.layoutRestored=true;
 await page.getByRole('button',{name:'转交到 Coding…',exact:true}).click();await page.getByRole('dialog',{name:'转交到 Coding Agent'}).waitFor();await page.getByLabel('转交的需求材料与目标').fill('明确转交：请核对这一个文件');await page.getByRole('button',{name:'确认转交草稿',exact:true}).click();await page.getByTestId('coding-prompt').waitFor();assert.match(await page.getByTestId('coding-prompt').inputValue(),/明确转交：请核对这一个文件/);assert.equal(calls.filter(x=>x.kind==='approval').length,0);checks.explicitHandoffDoesNotExecute=true;
 await assistant('work');await page.getByTestId('assistant-prompt').fill('取消这个合成工作');await page.getByTestId('assistant-goal').fill('取消验收');await page.getByTestId('assistant-deliverable').fill('不应生成的文稿');await page.getByTestId('assistant-confirmed').check();hold=true;await page.getByTestId('assistant-send').click();await page.getByTestId('assistant-cancel').waitFor();const cancelId=new URL(page.url()).searchParams.get('conversation');await page.getByTestId('assistant-cancel').click();await page.getByTestId('assistant-cancel').waitFor({state:'hidden'});const cancelled=conversations.get(key(cancelId));assert.equal(cancelled.turns[0].status,'CANCELLED');assert.equal(cancelled.turns[0].artifacts.length,0);const beforeReload=jobCount();await page.reload();await page.getByTestId('assistant-prompt').waitFor();assert.equal(jobCount(),beforeReload);checks.cancelRestoresWithoutArtifact=true;hold=false;
 await go(new URL(workUrl).pathname+new URL(workUrl).search);await page.getByText('成果文稿.docx',{exact:true}).first().waitFor();
 await page.screenshot({path:path.join(out,'work-desktop.png')});
 await go('/work?session='+sid+'&project='+project.id);await page.getByTestId('coding-approval').waitFor();assert.equal(new URL(page.url()).pathname,'/coding');assert.equal(new URL(page.url()).searchParams.get('session'),sid);checks.legacyCodingLink=true;
 assert.ok((await page.locator('body').innerText()).includes('既有代码项目'));checks.projectAssociation=true;
 await page.getByTestId('approval-allow').click();await page.getByText('文件已保存',{exact:true}).waitFor();await page.getByTestId('coding-files').click();await page.getByText('新增 · review.txt',{exact:true}).waitFor();assert.ok(wrote);checks.codingReview=true;
 await page.reload();await page.getByText('文件已保存',{exact:true}).waitFor();assert.equal(calls.filter(x=>x.kind==='approval').length,1);checks.codingRefreshNoReexecution=true;
 await go('/work?mode=chat');await page.getByTestId('coding-workspace').waitFor();assert.equal(new URL(page.url()).pathname,'/super-agent');checks.legacyManusPreserved=true;
 await assistant('chat');denyJobs=true;await page.getByTestId('assistant-prompt').fill('超过额度的合成请求');await page.getByTestId('assistant-send').click();await page.getByRole('alert').filter({hasText:'共享账号额度不足'}).waitFor();const denied=jobCount();await page.reload();await page.getByTestId('assistant-prompt').waitFor();assert.equal(jobCount(),denied);checks.denialNoAutomaticRetry=true;denyJobs=false;
 await assistant('chat');await page.getByTestId('assistant-prompt').fill('异步提交后立即切换');delayNext=300;const raceCount=jobCount();await page.getByTestId('assistant-send').click();await page.getByTestId('assistant-mode-work').click();await page.waitForTimeout(500);assert.equal(jobCount(),raceCount+1);assert.equal(calls.filter(x=>x.kind==='job').at(-1).mode,'chat');assert.equal(await page.getByTestId('assistant-mode-work').getAttribute('aria-pressed'),'true');assert.ok(!(await page.locator('body').innerText()).includes('模拟简短回答'));checks.submitThenSwitchStaysInOriginalScope=true;
 owner='owner-b';await go(new URL(chatUrl).pathname+new URL(chatUrl).search);await page.getByRole('alert').waitFor();assert.ok(!(await page.locator('body').innerText()).includes('模拟简短回答'));checks.uiDoesNotDisplayDeniedAccountHistory=true;
 await assistant('work');assert.equal(await page.getByTestId('assistant-prompt').inputValue(),'');assert.equal(await page.getByTestId('partner-model-select').inputValue(),'system');checks.ownerPreferencesIsolated=true;
 await page.setViewportSize({width:390,height:844});await page.reload();await page.getByTestId('assistant-prompt').waitFor();assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));const box=await page.getByTestId('assistant-prompt').boundingBox();assert.ok(box.x>=0&&box.x+box.width<=390);checks.mobileFits=true;await page.waitForFunction(()=>document.querySelector('.studio-sidebar')?.getBoundingClientRect().right<=0);await page.screenshot({path:path.join(out,'work-mobile.png'),animations:'disabled'});
 assert.deepEqual(errors,[]);assert.deepEqual(unknown,[]);checks.noPageErrors=true;checks.noUnknownApis=true;
}catch(e){await fs.writeFile(path.join(out,'failure-detail.json'),JSON.stringify({error:String(e),url:page.url(),body:await page.locator('body').innerText()},null,2));await page.screenshot({path:path.join(out,'failure.png'),fullPage:true});throw e;}finally{await fs.writeFile(path.join(out,'report.json'),JSON.stringify({checks,errors,unknown,modelSubmissions:jobCount(),paidModelRequests:0,scope:'Intercepted browser fixtures; backend security requires separate tests.'},null,2));await browser.close();}
console.log(JSON.stringify(checks));
