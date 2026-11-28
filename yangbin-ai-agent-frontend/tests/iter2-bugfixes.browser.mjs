import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

// Fault-injected UI contracts, served from the production build. No live model/API calls.
const frontend = fileURLToPath(new URL('../', import.meta.url));
const dist = path.join(frontend, 'dist');
const catalog = JSON.parse(await fs.readFile(path.join(frontend, '../src/main/resources/work/catalog.json'), 'utf8'));
const checks = [], errors = [], unknown = [];
const stamp = '2026-11-28T00:00:00Z';
const markdown = '[外部资料](https://example.org/reference)\n\n```js\nconst longLine = "' + 'abcdef'.repeat(150) + '";\n```\n\n| Model | Cost | Latency | Note | More |\n| --- | --- | --- | --- | --- |\n| Balanced | -57.10% | 20.64ms | ' + 'wide_column_'.repeat(12) + ' | last |';
let loggedIn = true, denyJob = 400, healthFail = true, memoFail = false, legacyGets = 0, legacyStreams = 0, partnerJobs = 0, knowledgeUploads = 0, searches = 0;
let legacyStatus = 404, legacyCancels = 0;
let releaseDetail, releaseSearch, notifyDetail, notifySearch;
const detailStarted = new Promise(resolve => { notifyDetail = resolve; });
const searchStarted = new Promise(resolve => { notifySearch = resolve; });
const knowledgeDocs = ['a', 'b'].map(id => ({ id, filename: id + '.txt', state: 'READY', text: id + ' 的内容', chunks: 1, builtin: false }));
let connections = [{ id: 'connection-a', name: '连接 A', provider: 'openai', baseUrl: 'https://example.org/v1', hasKey: true }];
let profiles = [{ id: 'model-a', connectionId: 'connection-a', name: '模型 A', model: 'fixture', provider: 'openai', hasKey: true, reasoningEfforts: [] }];
let enabled = ['system'], defaultModelId = 'system', defaultFail = false;
const selectionWrites = [], jobs = [], conversations = new Map();
const models = () => ({ connections, allProfiles: profiles, profiles: profiles.filter(p => enabled.includes(p.id)), systemEnabled: enabled.includes('system'), defaultModelId,
  providers: [{ id: 'openai', name: 'OpenAI', defaultBaseUrl: 'https://example.org/v1' }] });
const browser = await chromium.launch({ headless: true, ...(process.env.CHROME_EXECUTABLE_PATH ? { executablePath: process.env.CHROME_EXECUTABLE_PATH } : {}) });
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
page.setDefaultTimeout(10000);
page.on('pageerror', error => errors.push(String(error)));
page.on('dialog', dialog => dialog.accept());
await page.route('**/*', async route => {
  const req = route.request(), url = new URL(req.url()), p = url.pathname, method = req.method();
  const reply = (json, status = 200) => route.fulfill({ json, status });
  if (p.startsWith('/api/')) {
    if (p === '/api/auth/me') return reply({ user: loggedIn ? { id: 'iter2-owner', username: 'iter2', role: 'admin', csrf: 'fixture' } : null });
    if (p === '/api/auth/login') return route.fulfill({ status: 502, contentType: 'text/html', body: '<html>Bad Gateway</html>' });
    if (p === '/api/ai/models') return reply(models());
    if (p === '/api/ai/models/connections/connection-a' && method === 'DELETE') {
      connections = []; profiles = []; enabled = enabled.filter(id => id === 'system'); return reply(models());
    }
    if (p === '/api/ai/models/selection') {
      const ids = req.postDataJSON().modelProfileIds; selectionWrites.push(ids);
      if (ids.some(id => id !== 'system' && !profiles.some(p => p.id === id))) return reply({ message: '模型 ID 无效' }, 400);
      enabled = ids; return reply(models());
    }
    if (p === '/api/ai/models/defaults') {
      if (defaultFail) return reply({ message: '无法切换默认模型' }, 400);
      defaultModelId = req.postDataJSON().modelProfileId; return reply(models());
    }
    if (p === '/api/ai/work/catalog') return reply(catalog);
    if (p === '/api/ai/work/library') return reply({ skills: [], partners: [] });
    if (p === '/api/ai/work/partners') return reply([]);
    if (p.startsWith('/api/ai/work/partners/office')) {
      const op = p.split('/')[6];
      if (!op) return reply({ partner: catalog.partners.find(p => p.id === 'office'), policy: { enabled: true } });
      if (op === 'summon') return reply({});
      if (op === 'resources' || op === 'conversations') return reply([]);
      if (op === 'files') return reply({ id: 'upload-new', name: '刚上传的材料.txt', mime: 'text/plain', kind: 'upload' });
      if (op === 'jobs') { partnerJobs++; return reply({ message: '当前请求暂不能接受' }, 422); }
    }
    if (p === '/api/ai/capabilities') return reply({ rag: true, tools: [], mcpTools: [] });
    if (p === '/api/ai/coding/capabilities') return reply({ available: true, shellEnabled: false, model: 'fixture' });
    if (p === '/api/ai/knowledge') {
      if (method === 'POST') { knowledgeUploads++; return reply({ ...knowledgeDocs[1], id: 'uploaded', filename: '成功上传.txt' }); }
      return reply(knowledgeDocs);
    }
    if (p === '/api/ai/knowledge/a') { await new Promise(resolve => { releaseDetail = resolve; notifyDetail(); }); return reply(knowledgeDocs[0]); }
    if (p === '/api/ai/knowledge/b') return reply(knowledgeDocs[1]);
    if (p === '/api/ai/rag/search') { searches++; await new Promise(resolve => { releaseSearch = resolve; notifySearch(); }); return reply([{ source: '过期检索结果', content: '旧查询的正文', score: 1 }]); }
    if (['/api/ai/coding/recent', '/api/ai/work/evidence/recent-reports', '/api/ai/coding/projects', '/api/ai/coding/sessions', '/api/ai/manus/conversations'].includes(p)) return reply([]);
    if (p === '/api/ai/manus/chat') { legacyStreams++; return reply({ message: '旧会话请求被拒绝' }, 404); }
    if (p.startsWith('/api/ai/manus/conversations/') && p.endsWith('/cancel')) { legacyCancels++; return reply({ message: '旧会话不存在' }, 404); }
    if (p.startsWith('/api/ai/manus/conversations/')) { legacyGets++; return reply({ message: legacyStatus === 404 ? '旧会话不存在' : '服务暂不可用' }, legacyStatus); }
    if (p.startsWith('/api/ai/assistant/partners/')) {
      const parts = p.split('/'), partnerId = parts[5], op = parts[6], cid = parts[7];
      if (op === 'jobs') {
        const input = req.postDataJSON(); jobs.push(input);
        if (denyJob) return reply({ message: '请求参数不合法，请修改后重试' }, denyJob);
        const data = { id: input.conversationId, partnerId, mode: input.mode, revision: 1, total: 1, nextBefore: null,
          turns: [{ id: input.requestId, text: input.text, answer: markdown, status: 'SUCCEEDED', attachments: [], artifacts: [], events: [] }] };
        conversations.set(data.id, data); return reply(data, 202);
      }
      if (op === 'conversations' && !cid) return reply([]);
      if (op === 'conversations' && conversations.has(cid)) return reply(conversations.get(cid));
      return reply({ message: '会话不存在' }, 404);
    }
    if (p === '/api/ai/governance') return reply({ day: '2026-11-28', timezone: 'Asia/Singapore', requests: 2, active: 0 }); // Deliberately no limits.
    if (p === '/api/ai/governance/events') return reply([]);
    if (p === '/api/ai/manus/memory') {
      if (method === 'PUT' && memoFail) return reply({ message: '便签保存失败' }, 503);
      return reply({ text: method === 'PUT' ? req.postDataJSON().text : '保留原始便签' });
    }
    if (p === '/api/ops/health') return healthFail ? reply({ message: '健康检查暂不可用' }, 500) : reply({ ok: true });
    if (p === '/api/open/blog/iter2') return reply({ id: 'iter2', title: '格式边界测试', tags: [], markdown, publishedAt: stamp });
    unknown.push(method + ' ' + p); return reply({ message: '未声明的测试接口' }, 404);
  }
  let file = path.join(dist, p);
  try { if (!(await fs.stat(file)).isFile()) throw Error(); } catch { file = path.join(dist, 'index.html'); }
  return route.fulfill({ body: await fs.readFile(file), contentType: ({ '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css' })[path.extname(file)] || 'application/octet-stream' });
});
const go = suffix => page.goto('http://iter2-fixture.test' + suffix);
const mark = name => { checks.push(name); console.log('PASS ' + name); };
async function noOverflow() {
  await page.waitForFunction(() => !document.querySelector('.studio-sidebar') || document.querySelector('.studio-sidebar').getBoundingClientRect().right <= 0 || innerWidth > 800);
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'document must not overflow horizontally');
}
async function markdownBounds(selector) {
  const bounds = await page.locator(selector).evaluate(el => {
    const pre = el.querySelector('pre'), table = el.querySelector('table'), cell = el.querySelector('td'), link = el.querySelector('a');
    const rect = pre.getBoundingClientRect();
    return { left: rect.left, right: rect.right, width: innerWidth, preScroll: pre.scrollWidth > pre.clientWidth,
      tableScroll: table.scrollWidth > table.clientWidth, cellWrap: getComputedStyle(cell).whiteSpace, wordBreak: getComputedStyle(cell).wordBreak,
      target: link.target, rel: link.rel };
  });
  assert.ok(bounds.left >= 0 && bounds.right <= bounds.width + 1, JSON.stringify(bounds));
  assert.ok(bounds.preScroll, 'long code must scroll inside pre');
  assert.ok(bounds.tableScroll, 'wide table must scroll inside table');
  assert.equal(bounds.cellWrap, 'nowrap'); assert.equal(bounds.wordBreak, 'keep-all');
  assert.equal(bounds.target, '_blank');
  assert.ok(bounds.rel.split(/\s+/).includes('noopener') && bounds.rel.split(/\s+/).includes('noreferrer'));
  await noOverflow();
}
try {
  await go('/assistant?mode=chat');
  await page.getByTestId('assistant-prompt').fill('首次提交必须回滚');
  await page.getByTestId('assistant-send').click();
  await page.getByRole('alert').filter({ hasText: '请求参数不合法' }).waitFor();
  await page.waitForFunction(() => !new URL(location.href).searchParams.has('conversation'));
  assert.equal(await page.getByTestId('assistant-prompt').inputValue(), '首次提交必须回滚');
  assert.equal(await page.getByTestId('assistant-send').isEnabled(), true);
  assert.ok(!(await page.locator('body').innerText()).includes('正在核对任务'));
  assert.deepEqual(await page.evaluate(() => Object.entries(sessionStorage).filter(([k]) => k.startsWith('workspace-v1:iter2-owner:assistant-') && k.endsWith(':last')).map(([,v]) => JSON.parse(v)).filter(v => v?.conversation)), []);
  assert.equal(jobs.length, 1); mark('P0-2 assistant 400 rolls back URL, pointer and send state');

  denyJob = 0; await page.getByTestId('assistant-send').click();
  await page.locator('.reply-markdown pre').waitFor();
  for (const width of [390, 1440]) { await page.setViewportSize({ width, height: 1000 }); await markdownBounds('.reply-markdown'); }
  mark('P1-3/P2-1 chat code/table contained at 390/1440; external links isolated');
  await page.setViewportSize({ width: 390, height: 844 });
  await page.getByTestId('assistant-history').click();
  const top = await page.locator('.coding-topbar').boundingBox(), history = await page.getByRole('dialog', { name: '助手历史' }).boundingBox();
  assert.ok(history.y >= top.y + top.height - 1); mark('P1-1 mobile assistant history stays below wrapped header');

  await go('/partners/office'); await page.getByLabel('发给伙伴的消息').fill('保留附件的失败请求');
  await page.locator('input[type=file]').setInputFiles({ name: '刚上传的材料.txt', mimeType: 'text/plain', buffer: Buffer.from('fixture') });
  await page.locator('.composer-attachments').getByText('刚上传的材料.txt', { exact: false }).waitFor();
  await page.getByRole('button', { name: '发送消息', exact: true }).click();
  await page.getByRole('alert').filter({ hasText: '当前请求暂不能接受' }).waitFor();
  await page.waitForFunction(() => !new URL(location.href).searchParams.has('conversation'));
  assert.equal(await page.getByLabel('发给伙伴的消息').inputValue(), '保留附件的失败请求');
  assert.ok(await page.locator('.composer-attachments').getByText('刚上传的材料.txt', { exact: false }).isVisible());
  assert.equal(partnerJobs, 1); assert.ok(await page.getByRole('button', { name: '发送消息', exact: true }).isEnabled());
  const composer = await page.locator('.task-composer').boundingBox(), sendButton = await page.getByRole('button', { name: '发送消息', exact: true }).boundingBox();
  assert.ok(sendButton.y + sendButton.height <= composer.y + composer.height + 1);
  await noOverflow(); mark('P0-2/P1-2 partner 422 restores fresh attachment, URL and mobile composer');

  await go('/skills'); await page.getByTestId('skill-hub').waitFor(); await page.locator('.category-strip button').nth(3).waitFor();
  await noOverflow();
  const strip = await page.locator('.category-strip').boundingBox(); assert.ok(strip.x >= 0 && strip.x + strip.width <= 391);
  mark('P1-3 mobile skills categories stay inside viewport');

  await go('/p/iter2'); await page.locator('.blog-markdown pre').waitFor();
  await markdownBounds('.blog-markdown'); mark('P1-3/P2-1 blog code/table contained and external links isolated');

  await page.setViewportSize({ width: 1440, height: 1000 }); await go('/settings');
  await page.getByTestId('select-model-a').check();
  await page.locator('.connections article').filter({ hasText: '连接 A' }).getByRole('button', { name: '删除', exact: true }).click();
  await page.getByText('连接已删除，历史任务保留。', { exact: true }).waitFor();
  assert.equal(await page.getByTestId('select-model-a').count(), 0);
  await page.getByTestId('selection-save').click();
  await page.getByText('可选模型已保存。各工作空间只显示这些模型。', { exact: true }).waitFor();
  assert.deepEqual(selectionWrites.at(-1), ['system']); mark('P0-3 deleting connection prunes unsaved model IDs before save');
  profiles = [{ id: 'model-b', name: '模型 B', model: 'fixture-b', provider: 'openai', hasKey: true }]; enabled = ['system', 'model-b']; defaultFail = true;
  await page.reload(); await page.getByTestId('default-model').selectOption('model-b');
  await page.getByRole('alert').filter({ hasText: '无法切换默认模型' }).waitFor();
  assert.equal(await page.getByTestId('default-model').inputValue(), 'system'); mark('P0-3 failed default model update restores native select');

  await go('/governance'); await page.getByRole('heading', { name: '今日模型调用' }).waitFor();
  assert.ok((await page.locator('body').innerText()).includes('未知'));
  await page.getByLabel('主对话参考便签', { exact: true }).fill('未保存内容必须保留'); memoFail = true;
  await page.getByRole('button', { name: '清空便签', exact: true }).click();
  await page.getByRole('alert').filter({ hasText: '便签保存失败' }).waitFor();
  assert.equal(await page.getByLabel('主对话参考便签', { exact: true }).inputValue(), '未保存内容必须保留');
  mark('P1-8 missing limits/health 500 render; failed memo clear preserves input');
  healthFail = false; await page.getByRole('button', { name: '刷新状态', exact: true }).click(); await page.getByTestId('gateway-health').waitFor();

  await go('/knowledge');
  await page.getByLabel('选择文件').setInputFiles({ name: 'oversized.txt', mimeType: 'text/plain', buffer: Buffer.alloc(1024 * 1024 + 1) });
  await page.getByRole('button', { name: '上传并建立索引', exact: true }).click();
  await page.getByRole('alert').filter({ hasText: '1 MB' }).waitFor(); assert.equal(knowledgeUploads, 0);
  await page.getByLabel('选择文件').setInputFiles({ name: '成功上传.txt', mimeType: 'text/plain', buffer: Buffer.from('fixture') });
  await page.getByRole('button', { name: '上传并建立索引', exact: true }).click();
  await page.getByRole('heading', { name: '成功上传.txt', exact: true }).waitFor();
  assert.equal(await page.getByLabel('选择文件').inputValue(), '');
  assert.ok(await page.getByRole('button', { name: '上传并建立索引', exact: true }).isDisabled()); assert.equal(knowledgeUploads, 1);
  const firstDetail = page.waitForResponse(r => r.url().endsWith('/api/ai/knowledge/a'));
  await page.getByRole('button', { name: /^a\.txt/ }).click();
  await detailStarted;
  await page.getByRole('button', { name: /^b\.txt/ }).click();
  await page.getByRole('heading', { name: 'b.txt', exact: true }).waitFor();
  releaseDetail(); await firstDetail;
  assert.equal(await page.getByRole('heading', { name: 'a.txt', exact: true }).count(), 0);
  await page.getByLabel('输入一个问题').fill('   '); assert.ok(await page.getByRole('button', { name: '检索知识库', exact: true }).isDisabled()); assert.equal(searches, 0);
  await page.getByLabel('输入一个问题').fill('旧查询');
  const searchResponse = page.waitForResponse(r => new URL(r.url()).pathname === '/api/ai/rag/search');
  await page.getByRole('button', { name: '检索知识库', exact: true }).click(); await searchStarted;
  await page.getByLabel('输入一个问题').fill('新的查询'); releaseSearch(); await searchResponse;
  await page.getByRole('button', { name: '检索知识库', exact: true }).waitFor();
  assert.equal(await page.getByText('旧查询的正文', { exact: true }).count(), 0);
  mark('P1-5 knowledge size guard, upload reset, out-of-order detail and stale search');

  await go('/super-agent'); await page.getByTestId('coding-prompt').fill('长'.repeat(1000));
  await page.getByTestId('coding-send').click(); await page.getByRole('alert').waitFor();
  assert.equal(legacyStreams, 0); assert.ok((await page.locator('body').innerText()).match(/过长|缩短|6000/));
  await page.getByTestId('coding-prompt').fill('短请求'); await page.getByTestId('coding-send').click();
  await page.getByRole('alert').filter({ hasText: '旧会话不存在' }).waitFor();
  assert.equal(await page.getByTestId('coding-cancel').count(), 0);
  const stopped = legacyGets; await page.waitForTimeout(2900); assert.equal(legacyGets, stopped);
  await page.getByTestId('coding-prompt').fill('可以再次编辑新请求');
  assert.equal(await page.getByTestId('coding-send').isEnabled(), true); mark('P0-1 GET length guard; SSE 404 recovery terminates polling');

  await go('/super-agent'); legacyStatus = 503;
  await page.getByTestId('coding-prompt').fill('停止请求返回404'); await page.getByTestId('coding-send').click();
  await page.getByTestId('coding-cancel').click(); await page.getByTestId('coding-cancel').waitFor({ state: 'hidden' });
  const afterCancel = legacyGets; await page.waitForTimeout(1400);
  assert.equal(legacyCancels, 1); assert.equal(legacyGets, afterCancel); mark('P0-1 cancel 404 does not restart recovery');
  await go('/super-agent'); const beforeRetry = legacyGets;
  await page.getByTestId('coding-prompt').fill('连续服务失败须有界'); await page.getByTestId('coding-send').click();
  await page.getByRole('alert').filter({ hasText: '连续 10 次' }).waitFor({ timeout: 35000 });
  assert.equal(legacyGets - beforeRetry, 10); assert.equal(await page.getByTestId('coding-cancel').count(), 0);
  await page.waitForTimeout(2700); assert.equal(legacyGets - beforeRetry, 10); mark('P0-1 transient failure recovery stops after exactly ten attempts');

  loggedIn = false; await go('/login'); await page.getByLabel('用户名', { exact: true }).fill('iter2user'); await page.getByLabel('密码', { exact: true }).fill('fixture-password');
  await page.getByRole('button', { name: '登录', exact: true }).click();
  assert.equal(await page.getByRole('alert').innerText(), '无法连接服务，请稍后重试');
  assert.ok(!(await page.locator('body').innerText()).match(/SyntaxError|Unexpected token|Failed to fetch/)); mark('P1-4 HTML 502 login failure is friendly Chinese');
  assert.deepEqual(errors, []); assert.deepEqual(unknown, []);
  console.log(JSON.stringify({ passed: checks.length, checks, pageErrors: errors, unknownRequests: unknown, paidModelCalls: 0 }));
} catch (error) {
  console.error(JSON.stringify({ error: String(error), checks, pageErrors: errors, unknownRequests: unknown, url: page.url(), body: await page.locator('body').innerText() }));
  throw error;
} finally { await browser.close(); }
