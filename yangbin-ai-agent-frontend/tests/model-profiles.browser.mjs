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
const catalog = () => ({ profiles, providers, defaultModelId });
const browser = await chromium.launch({ headless: true, ...(process.env.CHROME_EXECUTABLE_PATH ? { executablePath: process.env.CHROME_EXECUTABLE_PATH } : {}) });
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
page.on('pageerror', error => errors.push(String(error)));
await page.route('**/*', async route => {
 const req = route.request(), url = new URL(req.url()), name = url.pathname, method = req.method();
 if (name.startsWith('/api/')) {
  let data = {}, status = 200;
  if (name === '/api/auth/me') data = { user: { id: 'fixture-owner', username: '模型测试用户', role: 'admin', csrf: 'fixture' } };
  else if (name === '/api/ai/models') data = catalog();
  else if (name === '/api/ai/models/profiles' && method === 'POST') { const input = req.postDataJSON(); profileWrites.push(input); const { apiKey, ...publicFields } = input; data = { id: uid(), ...publicFields, hasKey: !!apiKey }; profiles.push(data); }
  else if (name.startsWith('/api/ai/models/profiles/')) {
   const id = name.split('/').at(-1), profile = profiles.find(item => item.id === id);
   if (method === 'DELETE') { profiles = profiles.filter(item => item.id !== id); if (defaultModelId === id) defaultModelId = 'system'; data = { deleted: true }; }
   else { const input = req.postDataJSON(); profileWrites.push(input); const { apiKey, ...publicFields } = input; Object.assign(profile, publicFields, { hasKey: profile.hasKey || !!apiKey }); data = profile; }
  } else if (name === '/api/ai/models/defaults') { defaultModelId = req.postDataJSON().modelProfileId; data = catalog(); }
  else if (name === '/api/ai/coding/capabilities') { const selected = url.searchParams.get('modelProfileId'); data = { available: profiles.some(item => item.id === selected && item.hasKey), reason: selected === 'system' || !selected ? '系统模型尚未配置，请添加个人模型。' : '', shellEnabled: false, model: selected }; }
  else if (name === '/api/ai/manus/chat') { chatIds.push(url.searchParams.get('modelProfileId')); return route.fulfill({ contentType: 'text/event-stream', body: 'data: 当前模型对话已完成。\n\ndata: [DONE]\n\n' }); }
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
const openManager = async () => { await page.getByTestId('model-picker').click(); await page.getByTestId('model-manage').click(); await page.getByTestId('model-manager').waitFor(); };
const closeManager = () => page.getByRole('button', { name: '关闭模型设置', exact: true }).click();
try {
 await page.goto('http://model-fixture.test/work'); await page.getByText('系统模型尚未配置，请添加个人模型。', { exact: false }).waitFor(); checks.unconfiguredGuidance = true;
 await openManager(); checks.fiveProviders = await page.getByTestId('model-provider').locator('option').count() === 5;
 await page.getByTestId('model-provider').selectOption('anthropic'); checks.presetFromServer = await page.getByTestId('model-base-url').inputValue() === 'https://api.anthropic.com' && await page.getByTestId('model-id').inputValue() === 'fixture-claude-model';
 await page.getByTestId('model-provider').selectOption('openai-compatible'); await page.getByTestId('model-name').fill('我的代码模型'); await page.getByTestId('model-base-url').fill('https://custom.example.test/v1'); await page.getByTestId('model-id').fill('custom-code-model'); await page.getByTestId('model-api-key').fill(secret);
 checks.passwordInput = await page.getByTestId('model-api-key').getAttribute('type') === 'password';
 await page.getByTestId('model-save').click(); await page.getByText('已保存。可以在输入框中选择此模型。', { exact: true }).waitFor(); await page.getByText('密钥已保存', { exact: true }).waitFor();
 checks.customProfileCreated = profiles.length === 1 && profiles[0].model === 'custom-code-model' && profiles[0].baseUrl === 'https://custom.example.test/v1';
 checks.keyClearedAfterSave = await page.getByTestId('model-api-key').inputValue() === '';
 checks.noKeyRendered = !(await page.locator('body').innerText()).includes(secret);
 checks.httpNotice = (await page.getByTestId('model-manager').innerText()).includes('当前连接未加密');
 await page.screenshot({ path: path.join(out, 'model-manager-desktop.png') });
 await page.getByTestId('model-name').fill('我的代码模型 A'); await page.getByTestId('model-save').click(); await page.getByText('已保存。可以在输入框中选择此模型。', { exact: true }).waitFor(); checks.emptyKeyPreserved = profileWrites.at(-1).apiKey === '' && profiles[0].hasKey;
 await page.getByTestId('model-set-default').click(); await page.getByText('账号默认模型已更新。已有选择保持不变。', { exact: true }).waitFor(); checks.setDefaultWorks = defaultModelId === profiles[0].id;
 const profileA = profiles[0].id; await closeManager(); await page.getByTestId('model-picker').filter({ hasText: '我的代码模型 A' }).waitFor();
 await page.getByTestId('project-picker').click(); await page.getByLabel('新项目名称').fill('模型选择测试'); await page.getByTestId('create-project').click(); await page.getByTestId('project-picker').filter({ hasText: '模型选择测试' }).waitFor();
 await page.getByTestId('coding-prompt').fill('使用我的模型创建文件'); failNextTurn = true; await page.getByTestId('coding-send').click(); await page.getByText('提交结果尚未确认。', { exact: false }).waitFor();
 checks.byokWithoutGlobalKey = submissions.length === 1 && submissions[0].modelProfileId === profileA;
 checks.pendingSelectionLocked = await page.getByTestId('model-picker').isDisabled() && await page.getByTestId('approval-policy').isDisabled();
 const requestBefore = structuredClone(submissions[0]); await page.reload(); await page.getByTestId('coding-prompt').waitFor(); await page.getByTestId('coding-send').click(); await page.getByTestId('coding-approval').waitFor();
 checks.retryFrozenRequest = submissions.length === 2 && isDeepStrictEqual(submissions[1], requestBefore);
 checks.runningSelectionLocked = await page.getByTestId('model-picker').isDisabled();
 checks.historyModelSnapshot = (await page.locator('.turn-model').first().innerText()).includes('custom-code-model');
 checks.noSecretInStorageOrUrl = await page.evaluate(key => !JSON.stringify({ session: { ...sessionStorage }, local: { ...localStorage }, url: location.href }).includes(key), secret);
 checks.onlyProfileIdPreference = await page.evaluate(() => Object.keys(sessionStorage).filter(key => key.startsWith('coding-model:')).every(key => sessionStorage[key] === 'system' || /^[a-f0-9-]{36}$/.test(sessionStorage[key])));
 await page.getByTestId('coding-cancel').click(); await page.getByTestId('coding-cancel').waitFor({ state: 'hidden' });
 await page.goto('http://model-fixture.test/super-agent'); await page.getByTestId('model-picker').waitFor(); checks.routeMetadataUpdated = (await page.title()).includes('AI超级智能体') && (await page.locator('meta[name=description]').getAttribute('content')).includes('全能助手'); await page.getByTestId('model-picker').click(); await page.getByTestId(`model-option-${profileA}`).click(); await page.getByTestId('coding-prompt').fill('对话使用私人模型'); await page.getByTestId('coding-send').click(); await page.getByText('当前模型对话已完成。', { exact: true }).waitFor(); checks.manusReceivesOnlyProfileId = chatIds.at(-1) === profileA;
 await page.getByTestId('model-picker').click(); await page.getByTestId('model-option-system').click(); await page.goto('http://model-fixture.test/work'); await page.getByTestId('model-picker').filter({ hasText: '我的代码模型 A' }).waitFor(); checks.modeSelectionIndependent = true;
 await openManager(); await page.getByTestId('model-delete').click(); checks.deleteRequiresConfirmation = profiles.length === 1 && (await page.getByTestId('model-delete').innerText()).includes('确认删除'); await page.getByTestId('model-delete').click(); await page.getByText('模型配置已删除，历史任务记录保留。', { exact: true }).waitFor(); checks.deleteRemovesProfile = profiles.length === 0; await closeManager(); await page.getByTestId('model-picker').filter({ hasText: '系统默认' }).waitFor(); checks.deletedSelectionFallsBack = true;
 await page.setViewportSize({ width: 390, height: 844 }); await page.reload(); await page.getByTestId('model-picker').waitFor(); await openManager(); const dialog = await page.getByTestId('model-manager').boundingBox(); checks.mobileDialogFits = dialog.x >= 0 && dialog.x + dialog.width <= 390 && dialog.y + dialog.height <= 844; checks.mobileNoHorizontalOverflow = await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth); await page.screenshot({ path: path.join(out, 'model-manager-mobile.png') }); await closeManager(); const composer = await page.getByTestId('coding-composer').boundingBox(); checks.mobileComposerVisible = composer.y + composer.height <= 844; await page.screenshot({ path: path.join(out, 'model-selector-mobile.png') });
 checks.noPageErrors = errors.length === 0; checks.noUnknownApis = unknown.length === 0;
 const report = { fixture: true, paidModelCalls: 0, checks, errors, unknown, passed: Object.values(checks).filter(Boolean).length, total: Object.keys(checks).length };
 await fs.writeFile(path.join(out, 'browser-report.json'), JSON.stringify(report, null, 2)); console.log(JSON.stringify(report)); assert.ok(Object.values(checks).every(Boolean));
} catch (error) { await page.screenshot({ path: path.join(out, 'failure.png'), fullPage: true }); await fs.writeFile(path.join(out, 'browser-failure.json'), JSON.stringify({ error: String(error), checks, errors, unknown, body: await page.locator('body').innerText() }, null, 2)); throw error; } finally { await browser.close(); }
