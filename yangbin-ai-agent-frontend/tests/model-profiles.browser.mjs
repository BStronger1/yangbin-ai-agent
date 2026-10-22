import { chromium } from 'playwright';
import fs from 'node:fs/promises';
import path from 'node:path';
import assert from 'node:assert/strict';
import { isDeepStrictEqual } from 'node:util';
import { fileURLToPath } from 'node:url';
const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const root = path.join(projectRoot, 'dist');
const out = path.resolve(process.env.EVIDENCE_DIR || path.join(projectRoot, 'tmp/model-profiles-ui'));
await fs.mkdir(out, { recursive: true });
const checks = {}, errors = [], unknown = [], submissions = [], chatIds = [], profileWrites = [];
let connections=[],enabled=['system'],failDiscovery=false,failSave=false,owner='fixture-owner';
const secret = 'fixture-only-key-not-a-real-credential';
const providers = [
 { id: 'openai', name: 'OpenAI', api: 'openai-completions', defaultBaseUrl: 'https://api.openai.com/v1', suggestedModels: ['fixture-openai-model'] },
 { id: 'anthropic', name: 'Claude', api: 'anthropic-messages', defaultBaseUrl: 'https://api.anthropic.com', suggestedModels: ['fixture-claude-model'] },
 { id: 'deepseek', name: 'DeepSeek', api: 'openai-completions', defaultBaseUrl: 'https://api.deepseek.com', suggestedModels: ['deepseek-chat'] },
 { id: 'kimi', name: 'Kimi', api: 'openai-completions', defaultBaseUrl: 'https://api.moonshot.cn/v1', suggestedModels: ['fixture-kimi-model'] },
 { id: 'openai-compatible', name: '自定义兼容服务', api: 'openai-completions', defaultBaseUrl: '', suggestedModels: [] }
];
let profiles = [], defaultModelId = 'system', projects = [], sessions = [], failNextTurn = false;
const stamp = '2026-10-18T04:00:00Z';
const uid = () => crypto.randomUUID();
const catalog = () => ({ profiles:profiles.filter(p=>enabled.includes(p.id)),allProfiles:profiles,connections,systemEnabled:enabled.includes('system'),providers,defaultModelId });
const browser = await chromium.launch({ headless: true, ...(process.env.CHROME_EXECUTABLE_PATH ? { executablePath: process.env.CHROME_EXECUTABLE_PATH } : {}) });
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
page.setDefaultTimeout(12000);
page.on('pageerror', error => errors.push(String(error)));
await page.route('**/*', async route => {
 const req = route.request(), url = new URL(req.url()), name = url.pathname, method = req.method();
 if (name.startsWith('/api/')) {
  let data = {}, status = 200;
  if (name === '/api/auth/me') data = { user: { id: owner, username: '模型测试用户', role: 'admin', csrf: 'fixture' } };
  else if (name === '/api/ai/models') data = catalog();
  else if (name === '/api/ai/models/connections' && method === 'POST') {const input=req.postDataJSON();profileWrites.push(input);if(failSave){failSave=false;status=400;data={message:'invalid fixture connection'}}else {connections.push({id:'connection-'+connections.length,name:input.name,provider:input.provider,baseUrl:input.baseUrl,hasKey:true});data=catalog();}}
  else if (name.match(/\/ai\/models\/connections\/[^/]+\/discover$/)) {if(failDiscovery){failDiscovery=false;status=502;data={message:'fixture discovery failed'}}else{const cid=name.split('/').at(-2);if(!profiles.some(p=>p.connectionId===cid))profiles.push(...Array.from({length:8},(_,i)=>({id:'model-'+i,connectionId:cid,name:i===0?'DeepSeek':i===1?'GPT':i===2?'不支持工具的模型':'可选模型 '+i,model:'fixture-model-'+i,provider:i===0?'deepseek':'openai',hasKey:true,reasoningEfforts:i===0?['low','high','max']:i===1?['low','medium','high']:[],codingSupported:i!==2,codingUnavailableReason:i===2?'此模型不支持编程工具调用':null})));data=catalog();}}
  else if (name.startsWith('/api/ai/models/connections/') && method === 'PUT') {profileWrites.push(req.postDataJSON());data=catalog();}
  else if (name === '/api/ai/models/selection') {enabled=req.postDataJSON().modelProfileIds; if(!enabled.includes(defaultModelId))defaultModelId=enabled[0];data=catalog();}
  else if (name === '/api/ai/work/catalog') data={partners:[],skills:[]};
  else if(name.startsWith('/api/ai/assistant/partners/')&&name.endsWith('/conversations'))data=[];
  else if (name === '/api/ai/models/profiles' && method === 'POST') { const input = req.postDataJSON(); profileWrites.push(input); const { apiKey, ...publicFields } = input; data = { id: uid(), ...publicFields, hasKey: true }; profiles.push(data); }
  else if (name.startsWith('/api/ai/models/profiles/')) {
   const id = name.split('/').at(-1), profile = profiles.find(item => item.id === id);
   if (method === 'DELETE') { profiles = profiles.filter(item => item.id !== id); if (defaultModelId === id) defaultModelId = 'system'; data = { deleted: true }; }
   else { const input = req.postDataJSON(); profileWrites.push(input); const { apiKey, ...publicFields } = input; Object.assign(profile, publicFields, { hasKey: profile.hasKey || !!apiKey }); data = profile; }
  } else if (name === '/api/ai/models/defaults') { defaultModelId = req.postDataJSON().modelProfileId; data = catalog(); }
  else if (name === '/api/ai/coding/capabilities') { const selected = url.searchParams.get('modelProfileId'); data = { available: profiles.some(item => item.id === selected && item.hasKey), reason: selected === 'system' || !selected ? '系统模型尚未配置，请添加个人模型。' : '', shellEnabled: false, model: selected }; }
  else if (name === '/api/ai/manus/chat') { chatIds.push({modelProfileId:url.searchParams.get('modelProfileId'),reasoningEffort:url.searchParams.get('reasoningEffort')}); return route.fulfill({ contentType: 'text/event-stream', body: 'data: 当前模型对话已完成。\n\ndata: [DONE]\n\n' }); }
  else if (name === '/api/ai/coding/projects') { if (method === 'POST') { data = { id: uid(), name: req.postDataJSON().name, createdAt: stamp }; projects.push(data); } else data = projects; }
  else if (name === '/api/ai/coding/sessions') { if (method === 'POST') { data = { id: uid(), ...req.postDataJSON(), status: 'IDLE', revision: 0, createdAt: stamp, updatedAt: stamp, events: [], turns: [] }; sessions.push(data); } else data = sessions; }
  else if (name.startsWith('/api/ai/coding/sessions/')) {
   const bits = name.split('/'), session = sessions.find(item => item.id === bits[5]), operation = bits[6];
   if (!session) { status = 404; data = { message: '会话不存在' }; }
   else if (operation === 'turns') {
    const input = req.postDataJSON(); submissions.push(input);
    if (failNextTurn) { failNextTurn = false; return route.abort('failed'); }
    session.status = 'WAITING_APPROVAL'; session.approvalMode = input.approvalMode; session.revision++;
    const profile = profiles.find(item => item.id === input.modelProfileId);
    session.turns.push({ id: input.requestId, prompt: input.prompt, approvalMode: input.approvalMode, modelProfileId: input.modelProfileId, modelSnapshot: { profileId: profile.id, name: profile.name, model: profile.model, provider: profile.provider }, status: session.status, createdAt: stamp });
    session.events.push({ seq: session.events.length + 1, turnId: input.requestId, type: 'approval_required', approvalId: uid(), toolName: 'write', arguments: { path: 'demo.txt', content: 'demo' }, reason: '需要修改文件' }); data = session;
   } else if (operation === 'cancel') { session.status = 'CANCELLED'; session.turns.at(-1).status = 'CANCELLED'; data = session; }
   else if (operation === 'diff') data = [];
   else data = session;
  } else { unknown.push(method + ' ' + name); status = 404; data = { message: 'unknown fixture API' }; }
  return route.fulfill({ status, json: data });
 }
 let file = path.join(root, name); try { if (!(await fs.stat(file)).isFile()) throw Error(); } catch { file = path.join(root, 'index.html'); }
 return route.fulfill({ body: await fs.readFile(file), contentType: ({ '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css' })[path.extname(file)] || 'application/octet-stream' });
});
const go=async path=>{await page.goto('http://model-fixture.test'+path)};
const ready=async()=>{await page.getByTestId('partner-model-select').waitFor();await page.waitForFunction(()=>!document.querySelector('[data-testid="partner-model-select"]').disabled)};
try {
 await go('/settings');await page.getByTestId('connection-save').waitFor();checks.globalSettingsRoute=true;
 checks.fiveProviders=await page.getByTestId('connection-provider').locator('option').count()===5;
 await page.getByTestId('connection-provider').selectOption('anthropic');checks.presetFromServer=await page.getByTestId('connection-base-url').inputValue()==='https://api.anthropic.com';
 await page.getByTestId('connection-provider').selectOption('openai-compatible');await page.getByTestId('connection-name').fill('我的模型网关');await page.getByTestId('connection-base-url').fill('https://fixture.test/v1');await page.getByTestId('connection-api-key').fill(secret);
 checks.passwordInput=await page.getByTestId('connection-api-key').getAttribute('type')==='password';failSave=true;await page.getByTestId('connection-save').click();await page.getByRole('alert').waitFor();checks.failedSaveClearsKey=await page.getByTestId('connection-api-key').inputValue()==='';checks.failedSaveNoConnection=connections.length===0;
 await page.getByTestId('connection-api-key').fill(secret);await page.getByTestId('connection-save').click();await page.getByTestId('discover-connection-0').waitFor();checks.keyClearedAfterSave=await page.getByTestId('connection-api-key').inputValue()==='';checks.singleKeyPerConnection=connections.length===1&&profileWrites.at(-1).apiKey===secret;
 failDiscovery=true;await page.getByTestId('discover-connection-0').click();await page.getByRole('alert').waitFor();checks.discoveryFailurePreservesSelection=enabled.length===1&&profiles.length===0;
 await page.getByTestId('discover-connection-0').click();await page.getByTestId('select-model-0').waitFor();checks.discoveryListsAll=profiles.length===8;checks.discoveredNotAutoEnabled=enabled.length===1;
 await page.getByTestId('select-system').uncheck();for(let i=0;i<7;i++)await page.getByTestId('select-model-'+i).check();await page.getByTestId('selection-save').click();await page.getByText('可选模型已保存。各工作空间只显示这些模型。',{exact:true}).waitFor();checks.noSixModelHardLimit=enabled.length===7;
 for(let i=2;i<7;i++)await page.getByTestId('select-model-'+i).uncheck();await page.getByTestId('selection-save').click();await page.waitForFunction(()=>document.querySelector('[data-testid="selection-save"]').disabled===false);checks.exactSelection=enabled.length===2&&!enabled.includes('system');
 await page.getByTestId('default-model').selectOption('model-1');await page.getByText('默认模型已更新；已有空间选择保持。',{exact:true}).waitFor();checks.defaultModel=defaultModelId==='model-1';
 checks.noKeyRendered=!(await page.locator('body').innerText()).includes(secret);checks.noSecretInStorage=await page.evaluate(key=>!JSON.stringify({session:{...sessionStorage},local:{...localStorage},url:location.href}).includes(key),secret);
 await page.screenshot({path:path.join(out,'settings-desktop.png'),fullPage:true});
 await go('/assistant?mode=chat');await ready();checks.onlyTwoChatOptions=await page.getByTestId('partner-model-select').locator('option').count()===2;checks.noChatCredentialInput=await page.locator('input[type=password]').count()===0;await page.getByTestId('partner-model-select').selectOption('model-0');await page.getByTestId('reasoning-effort').selectOption('max');await page.getByTestId('assistant-prompt').fill('聊天空间草稿');
 await page.getByTestId('assistant-mode-work').click();await ready();checks.workDefaultIndependent=await page.getByTestId('partner-model-select').inputValue()==='model-1'&&await page.getByTestId('reasoning-effort').inputValue()==='';await page.getByTestId('reasoning-effort').selectOption('low');await page.getByTestId('assistant-prompt').fill('工作空间草稿');
 await page.getByTestId('assistant-mode-chat').click();await ready();checks.chatModelEffortAndDraftRestored=await page.getByTestId('partner-model-select').inputValue()==='model-0'&&await page.getByTestId('reasoning-effort').inputValue()==='max'&&await page.getByTestId('assistant-prompt').inputValue()==='聊天空间草稿';
 await page.reload();await ready();checks.effortRefreshRestored=await page.getByTestId('reasoning-effort').inputValue()==='max';
 await go('/coding');await page.getByTestId('model-picker').waitFor();await page.getByTestId('model-picker').click();checks.onlyTwoCodingOptions=await page.locator('[data-testid^="model-option-"]').count()===2;await page.getByTestId('model-option-model-0').click();await page.getByTestId('reasoning-effort').selectOption('high');await page.getByTestId('project-picker').click();await page.getByLabel('新项目名称').fill('模型任务测试');await page.getByTestId('create-project').click();await page.getByTestId('project-picker').filter({hasText:'模型任务测试'}).waitFor();await page.getByTestId('coding-prompt').fill('使用指定推理强度修改文件');failNextTurn=true;await page.getByTestId('coding-send').click();await page.getByText('提交结果尚未确认。',{exact:false}).waitFor();checks.effortSentToCoding=submissions.length===1&&submissions[0].reasoningEffort==='high'&&submissions[0].modelProfileId==='model-0';checks.pendingSelectionLocked=await page.getByTestId('model-picker').isDisabled()&&await page.getByTestId('reasoning-effort').isDisabled();const frozen=structuredClone(submissions[0]);await page.reload();await page.getByTestId('coding-send').click();await page.getByTestId('coding-approval').waitFor();checks.retryFrozenModelAndEffort=isDeepStrictEqual(frozen,submissions[1]);checks.noSecretInTask=!JSON.stringify(submissions).includes(secret);checks.historySnapshot=(await page.locator('.turn-model').first().innerText()).includes('fixture-model-0');await page.getByTestId('coding-cancel').click();await page.getByTestId('coding-cancel').waitFor({state:'hidden'});
 await go('/super-agent');await page.getByTestId('model-picker').click();await page.getByTestId('model-option-model-1').click();await page.getByTestId('reasoning-effort').selectOption('medium');await page.getByTestId('coding-prompt').fill('历史对话模型强度');await page.getByTestId('coding-send').click();await page.getByText('当前模型对话已完成。',{exact:true}).waitFor();checks.legacyEffortRequest=chatIds.at(-1).modelProfileId==='model-1'&&chatIds.at(-1).reasoningEffort==='medium';
 await go('/settings');await page.getByTestId('select-model-2').check();await page.getByTestId('selection-save').click();await page.getByText('可选模型已保存。各工作空间只显示这些模型。',{exact:true}).waitFor();await go('/coding');await page.getByTestId('model-picker').click();checks.codingUnsupportedToolsDisabled=await page.getByTestId('model-option-model-2').isDisabled();await page.getByTestId('model-manage').click();await page.getByTestId('model-settings').waitFor();checks.codingSettingsNavigatesGlobal=page.url().endsWith('/settings');
 await go('/assistant?mode=chat');await ready();await page.getByTestId('partner-model-select').selectOption('model-2');checks.unsupportedNoFakeEffort=await page.getByTestId('reasoning-effort').count()===0&&await page.getByTestId('reasoning-unavailable').count()===1;
 enabled=['model-1'];await page.reload();await ready();checks.removedModelRequiresChoice=await page.getByTestId('partner-model-select').inputValue()===''&&await page.getByTestId('assistant-send').isDisabled();checks.noSystemFallback=await page.getByTestId('partner-model-select').locator('option[value=system]').count()===0;
 owner='fixture-other-owner';await page.reload();await ready();checks.accountPreferenceIsolated=await page.getByTestId('partner-model-select').inputValue()==='model-1'&&await page.getByTestId('reasoning-effort').inputValue()==='';
 await page.setViewportSize({width:390,height:844});await go('/settings');await page.getByTestId('connection-save').waitFor();checks.mobileSettingsNoOverflow=await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth);await page.screenshot({path:path.join(out,'settings-mobile.png'),fullPage:true});await go('/assistant?mode=chat');await ready();checks.mobileComposerNoOverflow=await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth);const composer=await page.locator('.coding-composer').boundingBox();checks.mobileComposerVisible=composer.x>=0&&composer.x+composer.width<=390&&composer.y+composer.height<=844;await page.screenshot({path:path.join(out,'model-picker-mobile.png')});
 checks.noPageErrors=errors.length===0;checks.noUnknownApis=unknown.length===0;const report={fixture:true,paidModelCalls:0,checks,errors,unknown,passed:Object.values(checks).filter(Boolean).length,total:Object.keys(checks).length};await fs.writeFile(path.join(out,'browser-report.json'),JSON.stringify(report,null,2));console.log(JSON.stringify(report));assert.ok(Object.values(checks).every(Boolean));
} catch(error){await page.screenshot({path:path.join(out,'failure.png'),fullPage:true});await fs.writeFile(path.join(out,'browser-failure.json'),JSON.stringify({error:String(error),checks,errors,unknown,body:await page.locator('body').innerText()},null,2));throw error;}finally{await browser.close()}
