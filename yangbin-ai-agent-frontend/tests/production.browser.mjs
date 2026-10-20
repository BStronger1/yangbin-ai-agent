import {chromium} from 'playwright';
import fs from 'node:fs/promises';import path from 'node:path';import assert from 'node:assert/strict';import {fileURLToPath} from 'node:url';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../dist'),out=path.resolve(process.env.EVIDENCE_DIR||'tmp/production-ui');await fs.mkdir(out,{recursive:true});
const checks={},errors=[],unknown=[],calls=[],conversations=new Map();let memory='',failed=false;
const stamp='2026-10-21T01:00:00Z';
const browser=await chromium.launch({headless:true,...(process.env.CHROME_EXECUTABLE_PATH?{executablePath:process.env.CHROME_EXECUTABLE_PATH}:{})});const page=await browser.newPage({viewport:{width:1440,height:1000}});page.on('pageerror',e=>errors.push(String(e)));
await page.route('**/*',async route=>{const req=route.request(),url=new URL(req.url()),name=url.pathname,method=req.method();
 if(name.startsWith('/api/')){let data={},status=200;
 if(name==='/api/auth/me')data={user:{id:'fixture-owner',username:'用户',role:'admin',csrf:'fixture'}};
 else if(name==='/api/ai/models')data={profiles:[],providers:[],defaultModelId:'system'};
 else if(name==='/api/ai/coding/capabilities')data={available:true,shellEnabled:false,model:'fixture'};
 else if(name==='/api/ai/coding/projects'||name==='/api/ai/coding/sessions')data=[];
 else if(name==='/api/ai/governance') {if(failed){status=503;data={message:'账本不可用，已阻止新调用'}}else data={day:'2026-10-21',timezone:'UTC',limits:{dailyRequests:100,dailyTokens:200000,accountConcurrency:2},requests:3,budgetChargedTokens:10000,knownTokens:1200,unknownUsageRequests:1,remainingTokens:190000,active:0,failures:1,scope:'当前实例；不含外部PPT'}}
 else if(name==='/api/ai/governance/events')data=[{traceId:'fixture-trace-1',provider:'fixture',model:'model',status:'UNKNOWN',inputTokens:null,outputTokens:null,durationMs:3000,requestId:'1234567890abcdef1234567890abcdef'}];
 else if(name==='/api/ops/health')data={gateway:'alive',production:false,tls:false};
 else if(name==='/api/ai/manus/memory'){if(method==='PUT')memory=req.postDataJSON().text;data={text:memory}}
 else if(name==='/api/ai/manus/chat'){const cid=url.searchParams.get('chatId'),rid=url.searchParams.get('requestId');assert.ok(cid&&rid);calls.push({cid,rid});let c=conversations.get(cid)||{id:cid,title:'连续任务',turns:[],updatedAt:stamp};c.turns.push({id:rid,prompt:url.searchParams.get('message'),answer:'已完成模拟回答',status:'SUCCEEDED'});conversations.set(cid,c);return route.fulfill({contentType:'text/event-stream',body:'data: 已完成模拟回答\n\ndata: [DONE]\n\n'})}
 else if(name==='/api/ai/manus/conversations')data=[...conversations.values()].map(c=>({...c,status:c.turns.at(-1).status}));
 else if(name.startsWith('/api/ai/manus/conversations/')){const cid=name.split('/')[5];if(method==='DELETE'){conversations.delete(cid);data={deleted:true}}else data=conversations.get(cid)}
 else{unknown.push(method+' '+name);status=404}
 return route.fulfill({status,json:data});}
 let file=path.join(root,name);try{if(!(await fs.stat(file)).isFile())throw Error()}catch{file=path.join(root,'index.html')}return route.fulfill({body:await fs.readFile(file),contentType:({'.html':'text/html','.js':'text/javascript','.css':'text/css'})[path.extname(file)]||'application/octet-stream'});
});
try{
 await page.goto('http://production-fixture.test/governance');await page.getByText('10,000 / 200,000',{exact:true}).waitFor();checks.budgetSummary=true;assert.match(await page.locator('tbody').innerText(),/未知 \/ 未知/);checks.unknownUsageNotZero=true;assert.match(await page.locator('body').innerText(),/费用未知/);checks.noInventedCost=true;await page.getByTestId('gateway-health').waitFor();checks.gatewayConfigVisible=true;
 await page.getByLabel('主对话参考便签').fill('回复使用中文；保留验收证据');await page.getByRole('button',{name:'保存便签',exact:true}).click();await page.getByText('已保存；下一轮主对话将使用此便签。').waitFor();assert.match(memory,/验收证据/);checks.explicitMemorySaved=true;await page.reload();await page.waitForFunction(()=>document.querySelector('textarea')?.value.includes('验收证据'));checks.memoryRestored=true;
 await page.getByRole('button',{name:'清空便签',exact:true}).click();await page.waitForFunction(()=>document.querySelector('textarea')?.value==='');assert.equal(memory,'');checks.memoryCleared=true;
 failed=true;await page.getByRole('button',{name:'刷新状态',exact:true}).click();await page.getByRole('alert').filter({hasText:'账本不可用'}).waitFor();checks.failuresVisible=true;failed=false;await page.getByRole('button',{name:'刷新状态',exact:true}).click();await page.getByRole('alert').waitFor({state:'detached'});
 await page.screenshot({path:path.join(out,'governance-desktop.png')});await page.setViewportSize({width:390,height:844});assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));checks.mobileFits=true;await page.screenshot({path:path.join(out,'governance-mobile.png')});await page.setViewportSize({width:1440,height:1000});
 await page.goto('http://production-fixture.test/work?mode=chat');await page.getByTestId('coding-prompt').fill('记住任务目标甲');await page.getByTestId('coding-send').click();await page.getByText('已完成模拟回答',{exact:true}).waitFor();assert.equal(calls.length,1);assert.ok(page.url().includes('chat='));checks.durableIdentifiers=true;
 await page.reload();await page.getByText('记住任务目标甲',{exact:true}).waitFor();assert.equal(calls.length,1);checks.refreshRestoresWithoutModelRequest=true;
 await page.getByTestId('coding-prompt').fill('继续这个目标');await page.getByTestId('coding-send').click();await page.waitForFunction(()=>document.querySelectorAll('.coding-user-message').length===2);assert.equal(calls.length,2);assert.equal(calls[0].cid,calls[1].cid);assert.notEqual(calls[0].rid,calls[1].rid);checks.turnsShareConversation=true;
 await page.getByTestId('coding-history').click();await page.getByText('连续任务',{exact:true}).waitFor();checks.chatHistoryListed=true;page.once('dialog',d=>d.accept());await page.getByRole('button',{name:'删除当前对话',exact:true}).click();await page.waitForFunction(()=>document.querySelectorAll('.coding-user-message').length===0);assert.equal(conversations.size,0);checks.explicitHistoryDeletion=true;
 assert.deepEqual(errors,[]);assert.deepEqual(unknown,[]);checks.noPageErrors=true;checks.noUnknownApis=true;
}finally{await fs.writeFile(path.join(out,'report.json'),JSON.stringify({checks,errors,unknown,modelRequests:calls.length,paidModelCalls:0},null,2));await browser.close()}
assert.ok(Object.values(checks).every(Boolean));console.log(JSON.stringify(checks));
