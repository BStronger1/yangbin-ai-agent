import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

// Production-build browser contracts. Every request is intercepted; no model calls.
const frontend = fileURLToPath(new URL('../', import.meta.url));
const dist = process.env.TEST_DIST || path.join(frontend, 'dist');
const out = path.resolve(process.env.OUTPUT_DIR || process.env.EVIDENCE_DIR || path.join(os.tmpdir(), 'yangbin-iter3-ui-router'));
await fs.mkdir(out, { recursive: true });
const catalog = JSON.parse(await fs.readFile(path.join(frontend, '../src/main/resources/work/catalog.json'), 'utf8'));
const checks = [], errors = [], unknown = [], requests = [], jobs = [], conversations = new Map();
const intents = {
  coding_debug_repair: '代码调试与修复', general_chat_qa: '通用对话问答', knowledge_rag_query: '知识库检索',
  local_file_workspace_op: '本地工作区操作', memory_manage_recall: '长期记忆管理', out_of_scope_or_unsafe: '越界与安全拒答',
  task_plan_clarify: '任务规划与澄清', tool_mcp_a2a_orchestration: '工具与多端编排', writing_blog_edit: '写作与博客编辑',
};
const markdown = '路由测试回复\n\n```javascript\nconst answer = 42;\nconsole.log(answer);\n```\n\n[外部资料](https://example.org/docs)\n\n| 模型 | 数值 |\n| --- | ---: |\n| Flash | 57.1 |\n\n<script>window.__unsafe = true</script>';
const baseDecision = {
  enabled: true, applied: true, shadowOnly: false, intent: 'coding_debug_repair', rawModel: 'Qwen/Qwen3-4B',
  chosenModel: 'deepseek-v4.1-flash-1017', tier: 'flash', confidence: 0.98, routeConfidence: 0.87,
  jointConfidence: 0.8526, routerLatencyMs: 20.6, budgetMode: 'balanced', estimatedSavingPct: 57.1,
  promoted: true, fallbackReason: null,
  candidateProbs: { 'Qwen/Qwen3-4B': 0.1, 'deepseek-v4.1-flash-1017': 0.7, 'deepseek-v4-pro-0813': 0.15, abstain: 0.05 },
};
let loggedIn = true, catalogRouter = undefined, capabilitiesRouter = undefined, nextDecision;
let routerStatusCode = 200;
let routerStatus = { enabled: true, shadowOnly: false, circuitOpen: false, totalDecisions: 120, appliedCount: 105,
  fallbackCount: 15, avgLatencyMs: 20.6, tierCounts: { local: 30, flash: 60, pro: 15, abstain: 15 } };
const profiles = [{ id: 'byok', name: '个人模型', model: 'fixture-byok', provider: 'openai', hasKey: true, reasoningEfforts: [] }];
function seed(id, decision) {
  const conversation = { id, partnerId: 'office', mode: 'chat', title: id, revision: 1, total: 1, nextBefore: null,
    turns: [{ id: id + '-turn', text: '请查看路由结果', answer: markdown, status: 'SUCCEEDED', attachments: [], artifacts: [], events: [], ...(decision ? { routerDecision: decision } : {}) }] };
  conversations.set(id, conversation); return conversation;
}
const browser = await chromium.launch({ headless: true, ...(process.env.CHROME_EXECUTABLE_PATH ? { executablePath: process.env.CHROME_EXECUTABLE_PATH } : {}) });
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
page.setDefaultTimeout(10000);
page.on('pageerror', error => errors.push(String(error)));
page.on('dialog', dialog => dialog.accept());
await page.route('**/*', async route => {
  const req = route.request(), url = new URL(req.url()), p = url.pathname, method = req.method();
  const reply = (json, status = 200) => route.fulfill({ json, status });
  if (p.startsWith('/api/')) {
    requests.push(method + ' ' + p);
    if (p === '/api/auth/me') return reply({ user: loggedIn ? { id: 'iter3-owner', username: 'iter3', role: 'admin', csrf: 'fixture' } : null });
    if (p === '/api/ai/models') return reply({ profiles, allProfiles: profiles, connections: [], providers: [], systemEnabled: true,
      defaultModelId: 'system', ...(catalogRouter === undefined ? {} : { router: catalogRouter }) });
    if (p === '/api/ai/work/catalog') return reply(catalog);
    if (p === '/api/ai/work/library') return reply({ skills: [], partners: [] });
    if (p === '/api/ai/work/partners') return reply([]);
    if (p === '/api/ai/capabilities') return reply({ rag: true, tools: [], mcpTools: [], ...(capabilitiesRouter === undefined ? {} : { router: capabilitiesRouter }) });
    if (p === '/api/ai/router/status') return reply(routerStatusCode === 200 ? routerStatus : { message: '未启用' }, routerStatusCode);
    if (p === '/api/ai/coding/capabilities') return reply({ available: true, shellEnabled: false, model: 'fixture' });
    if (['/api/ai/coding/recent', '/api/ai/work/evidence/recent-reports', '/api/ai/coding/projects', '/api/ai/coding/sessions', '/api/ai/manus/conversations', '/api/ai/knowledge'].includes(p)) return reply([]);
    if (p.startsWith('/api/ai/assistant/partners/')) {
      const parts = p.split('/'), partnerId = parts[5], op = parts[6], cid = parts[7];
      if (op === 'jobs') {
        const input = req.postDataJSON(); jobs.push(input);
        let conversation = conversations.get(input.conversationId);
        if (!conversation) conversation = { id: input.conversationId, partnerId, mode: input.mode, title: input.text, revision: 0, turns: [] };
        conversation.revision++; conversation.turns.push({ id: input.requestId, text: input.text, answer: markdown, status: 'SUCCEEDED',
          attachments: [], artifacts: [], events: [], ...(nextDecision ? { routerDecision: structuredClone(nextDecision) } : {}) });
        conversation.total = conversation.turns.length; conversations.set(conversation.id, conversation); return reply(conversation, 202);
      }
      if (op === 'conversations' && !cid) return reply([...conversations.values()]);
      if (op === 'conversations' && conversations.has(cid)) return reply(conversations.get(cid));
    }
    if (p === '/api/ai/governance') return reply({ day: '2026-11-28', timezone: 'Asia/Singapore', requests: 12, active: 0,
      budgetChargedTokens: 1000, knownTokens: 900, unknownUsageRequests: 1, limits: { dailyRequests: 500, dailyTokens: 100000, accountConcurrency: 2 } });
    if (p === '/api/ai/governance/events') return reply([]);
    if (p === '/api/ai/manus/memory') return reply({ text: '' });
    if (p === '/api/ops/health') return reply({ ok: true });
    unknown.push(method + ' ' + p); return reply({ message: '未声明的测试接口' }, 404);
  }
  if (p === '/downloads/desktop.json') return reply({ version: '0.4.0', filename: 'yangbin-ai-agent-windows-x64-0.4.0.zip', bytes: 1024, sha256: 'a'.repeat(64) });
  let file = path.join(dist, p);
  try { if (!(await fs.stat(file)).isFile()) throw Error(); } catch { file = path.join(dist, 'index.html'); }
  return route.fulfill({ body: await fs.readFile(file), contentType: ({ '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css' })[path.extname(file)] || 'application/octet-stream' });
});
const go = suffix => page.goto('http://iter3-fixture.test' + suffix);
const mark = name => { checks.push(name); console.log('PASS ' + name); };
const forbiddenProbes = () => requests.filter(item => /\/api\/ai\/(?:capabilities|router\/status)$/.test(item));
async function ready(suffix = '') {
  await go('/assistant?mode=chat' + suffix);
  await page.getByTestId('assistant-prompt').waitFor();
  await page.waitForFunction(() => document.querySelector('[data-testid="partner-model-select"]')?.value === 'system');
}
async function submit(text) {
  const count = jobs.length;
  await page.getByTestId('assistant-prompt').fill(text);
  await page.getByTestId('assistant-send').click();
  await page.waitForFunction(() => document.querySelector('[data-testid="assistant-prompt"]')?.value === '');
  assert.equal(jobs.length, count + 1);
}
async function noOverflow(label) {
  await page.waitForFunction(() => !document.querySelector('.studio-sidebar') || innerWidth > 800 || document.querySelector('.studio-sidebar').getBoundingClientRect().right <= 0);
  const bounds = await page.evaluate(() => ({ scroll: document.documentElement.scrollWidth, width: innerWidth }));
  assert.ok(bounds.scroll <= bounds.width, label + ': ' + JSON.stringify(bounds));
}
try {
  await ready();
  assert.equal(await page.getByTestId('router-budget-picker').count(), 0);
  assert.equal(await page.getByTestId('smart-router-badge').count(), 0);
  await submit('未激活的默认请求');
  assert.ok(!Object.hasOwn(jobs.at(-1), 'budgetMode'));
  assert.deepEqual(Object.keys(jobs.at(-1)).sort(), ['conversationId', 'requestId', 'expectedRevision', 'text', 'skillId', 'attachmentIds', 'reasoningEffort', 'modelProfileId', 'mode', 'brief', 'confirmed'].sort());
  assert.deepEqual(forbiddenProbes(), []);
  mark('router off: hidden controls, original POST fields, zero capabilities/status probes');
  seed('disabled-decision', { ...baseDecision, enabled: false });
  await ready('&conversation=disabled-decision'); await page.locator('.reply-markdown').waitFor();
  assert.equal(await page.getByTestId('smart-router-badge').count(), 0);
  assert.equal(await page.getByTestId('router-budget-picker').count(), 0);
  mark('explicit disabled historical decision keeps both router controls hidden');

  await ready('&router=1');
  const picker = page.getByTestId('router-budget-picker');
  await picker.waitFor();
  await submit('默认均衡预算'); assert.equal(jobs.at(-1).budgetMode, 'balanced');
  nextDecision = baseDecision;
  for (const [budgetMode, label] of [['economy', '省钱'], ['balanced', '均衡'], ['quality', '高质量']]) {
    await picker.getByRole('button', { name: label, exact: true }).click();
    await submit('验证预算 ' + budgetMode); assert.equal(jobs.at(-1).budgetMode, budgetMode);
  }
  mark('query activation: balanced default and all three budgetMode POST values');
  await page.getByTestId('partner-model-select').selectOption('byok');
  await picker.waitFor({ state: 'hidden' }); await submit('个人模型不传路由预算');
  assert.equal(jobs.at(-1).modelProfileId, 'byok'); assert.ok(!Object.hasOwn(jobs.at(-1), 'budgetMode'));
  await page.getByTestId('partner-model-select').selectOption('system'); await picker.waitFor();
  mark('BYOK hides budget picker and omits budgetMode');

  const richId = 'rich-route'; seed(richId, baseDecision);
  await ready('&conversation=' + richId);
  await picker.waitFor();
  const badge = page.getByTestId('smart-router-badge');
  await badge.waitFor();
  for (const text of ['64M 智能路由', '代码调试与修复', 'DeepSeek Flash', '20.6', '57.1%']) assert.ok((await badge.innerText()).includes(text), text);
  assert.equal(await page.getByTestId('smart-router-panel').count(), 0);
  await badge.click(); const panel = page.getByTestId('smart-router-panel'); await panel.waitFor();
  assert.ok((await panel.innerText()).includes('coding_debug_repair'));
  assert.ok((await panel.innerText()).includes('Qwen/Qwen3-4B'));
  assert.ok((await panel.innerText()).match(/升档/));
  assert.equal(await panel.getByTestId('router-candidate').count(), 4);
  for (const tier of ['local', 'flash', 'pro', 'abstain']) assert.equal(await panel.locator(`[data-testid="router-candidate"][data-tier="${tier}"]`).count(), 1);
  for (const [tier, pct] of [['local', '10'], ['flash', '70'], ['pro', '15'], ['abstain', '5']]) {
    const row = panel.locator(`[data-testid="router-candidate"][data-tier="${tier}"]`);
    assert.equal(await row.getByRole('meter').getAttribute('aria-valuenow'), pct);
    assert.equal(await row.getByRole('meter').locator('span').evaluate(el => el.style.width), pct + '%');
  }
  assert.match(await panel.locator('[data-tier="flash"]').getAttribute('class'), /chosen/);
  await badge.click(); await panel.waitFor({ state: 'hidden' });
  mark('conversation activation; badge summary, expansion, promoted model path and four probabilities');

  for (const [intent, label] of Object.entries(intents)) {
    seed('intent-' + intent, { ...baseDecision, intent });
    await ready('&conversation=intent-' + intent);
    await page.getByTestId('smart-router-badge').waitFor();
    assert.ok((await page.getByTestId('smart-router-badge').innerText()).includes(label));
  }
  mark('all nine intent keys map to their Chinese labels');
  for (const [tier, model, label] of [['local', 'Qwen/Qwen3-4B', 'Qwen3-4B'], ['pro', 'deepseek-v4-pro-0813', 'DeepSeek Pro']]) {
    seed('tier-' + tier, { ...baseDecision, tier, rawModel: model, chosenModel: model, promoted: false });
    await ready('&conversation=tier-' + tier);
    await page.getByTestId('smart-router-badge').waitFor(); assert.ok((await page.getByTestId('smart-router-badge').innerText()).includes(label));
  }
  mark('local and pro tiers show correct model labels');
  seed('shadow', { ...baseDecision, applied: false, shadowOnly: true }); await ready('&conversation=shadow');
  await page.getByTestId('smart-router-badge').getByText('影子观测', { exact: true }).waitFor();
  for (const reason of ['timeout', 'circuit_open', 'router_abstain', 'tier_unconfigured']) {
    seed('fallback-' + reason, { ...baseDecision, applied: false, tier: 'abstain', fallbackReason: reason });
    await ready('&conversation=fallback-' + reason);
    await page.getByTestId('smart-router-badge').waitFor();
    assert.ok((await page.getByTestId('smart-router-badge').innerText()).includes('已安全回退'));
    assert.ok(!(await page.getByTestId('smart-router-badge').innerText()).includes('省 57.1%'));
    await page.getByTestId('smart-router-badge').click();
    assert.ok((await page.getByTestId('smart-router-panel').innerText()).match(/默认云端|默认模型/));
  }
  mark('shadow observation and four fallback reasons render without savings claim');

  await ready('&conversation=' + richId);
  const copy = page.locator('.reply-markdown .reply-code-copy').first();
  await copy.waitFor();
  assert.ok((await page.locator('.reply-code-toolbar').first().innerText()).includes('javascript'));
  await page.evaluate(() => Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: async text => { window.__copied = text; } } }));
  await copy.click(); await page.waitForFunction(() => document.querySelector('.reply-code-copy')?.textContent.includes('已复制'));
  assert.equal(await page.evaluate(() => window.__copied), 'const answer = 42;\nconsole.log(answer);\n');
  await page.waitForFunction(() => document.querySelector('.reply-code-copy')?.textContent.trim() === '复制');
  await page.evaluate(() => {
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: undefined });
    document.execCommand = command => { window.__legacyCopy = { command, text: document.activeElement.value }; return true; };
  });
  await copy.click(); await page.waitForFunction(() => document.querySelector('.reply-code-copy')?.textContent.includes('已复制'));
  assert.deepEqual(await page.evaluate(() => window.__legacyCopy), { command: 'copy', text: 'const answer = 42;\nconsole.log(answer);\n' });
  assert.equal(await page.evaluate(() => window.__unsafe), undefined);
  const link = page.locator('.reply-markdown a').first(); assert.equal(await link.getAttribute('target'), '_blank');
  assert.match(await link.getAttribute('rel'), /noopener/); assert.match(await link.getAttribute('rel'), /noreferrer/);
  mark('code language/copy feedback, Clipboard API and fallback preserve exact code and HTML safety');

  catalogRouter = { enabled: true }; await ready(); await picker.waitFor();
  mark('existing model catalog activates router without new HTTP probes');
  catalogRouter = undefined;
  await page.evaluate(() => localStorage.setItem('yangbin_router_preview', '1')); await ready(); await picker.waitFor();
  await page.evaluate(() => localStorage.removeItem('yangbin_router_preview'));
  assert.deepEqual(forbiddenProbes(), []);
  mark('local preview activation and all assistant flows remain probe-free');

  capabilitiesRouter = { enabled: true }; await go('/governance');
  const telemetry = page.getByTestId('router-telemetry'); await telemetry.waitFor();
  for (const text of ['64M 智能路由引擎遥测', '灰度已启用', '健康', '120', '105', '15', '20.6']) {
    await page.waitForFunction(text => document.querySelector('[data-testid="router-telemetry"]')?.textContent.includes(text), text);
  }
  assert.equal(requests.filter(r => r === 'GET /api/ai/router/status').length, 1);
  routerStatus = { ...routerStatus, shadowOnly: true, circuitOpen: true };
  await page.getByRole('button', { name: '刷新状态', exact: true }).click();
  await telemetry.getByText('影子模式', { exact: true }).waitFor(); await telemetry.getByText('熔断器：熔断冷却中', { exact: false }).waitFor();
  mark('governance telemetry counters, distributions, shadow and circuit states');
  routerStatusCode = 404; await go('/governance'); await telemetry.waitFor();
  await page.getByRole('button', { name: '刷新状态', exact: true }).waitFor(); assert.equal(await page.getByRole('alert').count(), 0);
  mark('governance status 404 degrades silently');
  const statusCount = requests.filter(r => r === 'GET /api/ai/router/status').length;
  capabilitiesRouter = undefined; await go('/governance'); await page.getByRole('heading', { name: '今日模型调用' }).waitFor();
  await page.getByRole('button', { name: '刷新状态', exact: true }).waitFor();
  assert.equal(requests.filter(r => r === 'GET /api/ai/router/status').length, statusCount);
  assert.equal(await page.getByRole('alert').count(), 0);
  mark('governance default-off skips status request and stays error-free');

  routerStatusCode = 200; routerStatus = { ...routerStatus, shadowOnly: false, circuitOpen: false }; capabilitiesRouter = { enabled: true };
  for (const width of [1440, 1280, 390]) {
    await page.setViewportSize({ width, height: width === 390 ? 844 : 1000 });
    await ready('&conversation=' + richId); await page.getByTestId('smart-router-badge').click();
    await noOverflow('assistant expanded ' + width); await page.screenshot({ path: path.join(out, `assistant-${width}.png`), animations: 'disabled' });
    for (const route of ['/governance', '/knowledge', '/settings', '/coding', '/download']) {
      await go(route); await page.waitForLoadState('networkidle'); await noOverflow(route + ' ' + width);
      if (route === '/governance') await page.screenshot({ path: path.join(out, `governance-${width}.png`), animations: 'disabled', fullPage: true });
    }
    loggedIn = false; await go('/login'); await page.getByLabel('用户名', { exact: true }).waitFor();
    const brand = await page.locator('body').textContent();
    for (const text of ['把想法', '变成下一步。', '64M 智能路由', 'Coding Agent', '知识库 RAG', 'MCP 生态']) assert.ok(brand.includes(text), text);
    assert.ok(!brand.includes('约会方案')); assert.ok(!brand.includes('约会规划'));
    await noOverflow('auth ' + width); await page.screenshot({ path: path.join(out, `auth-${width}.png`), animations: 'disabled' });
    loggedIn = true;
  }
  mark('1440/1280/390: expanded assistant, governance, knowledge, settings, coding, download, auth fit viewport');
  assert.deepEqual(errors, []); assert.deepEqual(unknown, []);
  mark('zero pageerror and zero unknown API requests');
} catch (error) {
  await page.screenshot({ path: path.join(out, 'failure.png'), fullPage: true });
  await fs.writeFile(path.join(out, 'failure.json'), JSON.stringify({ error: String(error), checks, errors, unknown, requests, url: page.url(), body: await page.locator('body').innerText() }, null, 2));
  throw error;
} finally {
  await fs.writeFile(path.join(out, 'report.json'), JSON.stringify({ passed: checks.length, checks, pageErrors: errors, unknownRequests: unknown, modelSubmissions: jobs.length, paidModelCalls: 0, scope: 'Local production build with intercepted API fixtures' }, null, 2));
  await browser.close();
}
console.log(JSON.stringify({ passed: checks.length, pageErrors: errors, unknownRequests: unknown, output: out }));
