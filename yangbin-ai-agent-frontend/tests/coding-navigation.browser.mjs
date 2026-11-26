import { chromium } from 'playwright';
import fs from 'node:fs/promises';
import path from 'node:path';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';

// Real built Vue components and Router navigation; only backend/native APIs are fixtures.
// This verifies UI lifetime and RPC dispatch, not Electron filesystem or model quality.
const frontend = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const sourceDist = path.resolve(process.env.FRONTEND_DIST || path.join(frontend, 'dist'));
const out = path.resolve(process.env.EVIDENCE_DIR || path.join(frontend, 'tmp/coding-navigation-ui'));
await fs.mkdir(out, { recursive: true });
// Other local tasks may rebuild dist during this longer navigation test. Serve
// one immutable build so later lazy route imports cannot disappear mid-session.
const dist = path.join(out, 'built-frontend-' + crypto.randomUUID());
await fs.cp(sourceDist, dist, { recursive: true });
const browser = await chromium.launch({ headless: true, ...(process.env.CHROME_EXECUTABLE_PATH ? { executablePath: process.env.CHROME_EXECUTABLE_PATH } : {}) });
const checks = {}, errors = [], unknown = [];
const check = (name, value = true) => { assert.ok(value, name); checks[name] = true; console.log('CHECK ' + name); };
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
async function until(predicate, message, timeout = 12000) {
  const end = Date.now() + timeout;
  while (!predicate()) { if (Date.now() >= end) throw new Error(message); await pause(30); }
}

async function fixture(kind) {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
  const page = await context.newPage(); page.setDefaultTimeout(8000);
  const project = { id: kind + '-project', name: kind === 'local' ? '导航候选项目' : '服务端导航项目', source: kind };
  const state = { owner: 'owner-a', session: null, pending: [], results: [], claims: [], polls: [], sessionReads: [], turns: [], cancels: 0, clientId: null, turnId: null };
  const record = () => state.session;
  const append = (type, fields = {}) => {
    const event = { type, turnId: state.turnId, seq: state.session.events.length + 1, ...fields };
    state.session.events.push(event); state.session.revision++;
  };
  const finish = answer => { state.session.status = 'SUCCEEDED'; state.session.turns.at(-1).status = 'SUCCEEDED'; append('text_delta', { delta: answer }); };
  const request = (operation, args) => {
    const item = { requestId: crypto.randomUUID(), operation, arguments: args, turnId: state.turnId, expiresAt: Date.now() + 60000, claimed: false };
    state.pending.push(item); return item;
  };
  if (kind === 'local') {
    await page.addInitScript(() => {
      const deviceId = 'fa64a420-2411-4af6-a9c7-fc63de9d7f98';
      const receipts = new Map(); window.__nativeCalls = [];
      const handle = { desktop: true, id: 'native-handle', name: 'navigation-project', path: 'fixture-only', platform: 'win32', deviceId };
      window.yangbinDesktop = {
        version: 2, workbenchProtocol: 1,
        restore: async owner => owner === 'owner-a' ? handle : null,
        bind: async () => true,
        execute: async (id, operation, args, requestId, execution) => {
          window.__nativeCalls.push({ id, operation, args, requestId, execution, path: location.pathname });
          if (operation === 'begin_turn') return { output: '', changes: [] };
          if (operation === 'ping') return { output: JSON.stringify({ repoTools: true, candidateProtocol: 1, localProcesses: false, publicTests: false, platform: 'win32' }) };
          if (operation === 'process_status') return { output: '[]' };
          if (operation === 'read') {
            if (args.path === 'delayed-read.txt') await new Promise(resolve => { window.__releaseNativeRead = resolve; });
            return { output: 'before\n' };
          }
          if (operation === 'edit') {
            const receipt = { operationId: requestId, phase: 'CONFIRMED', effect: 'CANDIDATE', bytesMatch: true, writerAlive: false, baselineMatches: true,
              candidate: { path: args.path, before: 'before\n', after: 'after\n', baseHash: 'a'.repeat(64), afterHash: 'b'.repeat(64) } };
            receipts.set(requestId, receipt); return { output: '候选已保存，原文件未修改：' + args.path, receipt };
          }
          throw new Error('Unexpected native operation: ' + operation);
        },
        reconcile: async (_id, { requestId }) => receipts.get(requestId) || { operationId: requestId, phase: 'MISSING', effect: 'CANDIDATE' },
      };
    });
  } else {
    state.turnId = 'server-turn';
    state.session = { id: 'server-session', projectId: project.id, title: '后台持续执行', approvalMode: 'on-risk', status: 'RUNNING', revision: 1,
      turns: [{ id: state.turnId, prompt: '服务端任务继续执行', status: 'RUNNING' }], events: [] };
  }
  page.on('pageerror', error => errors.push(kind + ': ' + String(error)));
  await page.route('**/*', async route => {
    const req = route.request(), url = new URL(req.url()), name = url.pathname, method = req.method();
    if (name.startsWith('/api/')) {
      const body = req.postData() ? req.postDataJSON() : null;
      let data = {}, status = 200;
      if (name === '/api/auth/me') data = { user: state.owner ? { id: state.owner, username: state.owner, role: 'admin', csrf: 'fixture' } : null };
      else if (name === '/api/auth/logout') { state.owner = null; data = { ok: true }; }
      else if (name === '/api/auth/login') { state.owner = 'owner-b'; data = { user: { id: state.owner, username: state.owner, role: 'admin', csrf: 'fixture-b' } }; }
      else if (name === '/api/ai/models') data = { profiles: [], allProfiles: [], connections: [], providers: [], systemEnabled: true, defaultModelId: 'system' };
      else if (name === '/api/ai/coding/capabilities') data = { available: true, shellEnabled: false };
      else if (name === '/api/ai/coding/projects') data = [project];
      else if (name === '/api/ai/coding/sessions' && method === 'GET') data = state.owner === 'owner-a' && state.session ? [record()] : [];
      else if (name === '/api/ai/coding/sessions' && method === 'POST') {
        state.createStarted = true;
        if (state.pauseCreate) await new Promise(resolve => { state.releaseCreate = resolve; });
        state.session = { id: 'local-session', projectId: project.id, title: body.title, approvalMode: body.approvalMode, status: 'IDLE', revision: 0, turns: [], events: [] }; data = record();
      }
      else if (/\/sessions\/[^/]+$/.test(name)) {
        state.sessionReads.push(name.split('/').at(-1));
        state.polls.push({ type: 'session', path: new URL(page.url()).pathname, owner: state.owner });
        if (state.owner !== 'owner-a') { status = 403; data = { message: 'other owner' }; } else data = record();
      }
      else if (name.endsWith('/events')) { status = 404; data = {}; }
      else if (name.endsWith('/turns')) {
        state.turnStarted = true;
        if (state.pauseTurn) await new Promise(resolve => { state.releaseTurn = resolve; });
        state.turns.push(body); state.clientId = body.localClientId; state.turnId = body.requestId;
        state.session.status = 'RUNNING'; state.session.revision++;
        state.session.turns.push({ id: body.requestId, prompt: body.prompt, status: 'RUNNING', approvalMode: body.approvalMode });
        request('ping', {}); data = record();
      }
      else if (name.endsWith('/local-files')) {
        state.polls.push({ type: 'local', path: new URL(page.url()).pathname, owner: state.owner });
        assert.equal(req.headers()['x-local-client'], state.clientId);
        data = state.pending;
      }
      else if (name.endsWith('/local-files/claim')) {
        assert.equal(body.clientId, state.clientId); assert.equal(body.protocol, 1);
        const item = state.pending.find(item => item.requestId === body.requestId); assert.ok(item && !item.claimed, 'no duplicate claim');
        item.claimed = true; state.claims.push(item.requestId); data = item;
      }
      else if (name.endsWith('/local-files/result')) {
        const item = state.pending.find(item => item.requestId === body.requestId); assert.ok(item, 'result belongs to pending request');
        assert.equal(body.ok, true, body.output); state.results.push({ operation: item.operation, path: new URL(page.url()).pathname, ...body });
        state.pending = state.pending.filter(item => item.requestId !== body.requestId);
        if (item.operation === 'edit') finish('离开工作台后候选仍完成');
        data = { ok: true };
      }
      else if (name.endsWith('/local-receipts')) data = method === 'GET' ? [] : { confirmed: true };
      else if (name.endsWith('/cancel')) { state.cancels++; state.session.status = 'CANCELLED'; data = record(); }
      else if (name.endsWith('/recovery')) data = { required: false, operations: [] };
      else if (name === '/api/ai/work/catalog') data = { partners: [{ id: 'office', name: '通用办公伙伴' }], skills: [] };
      else if (name === '/api/ai/coding/recent' || name === '/api/ai/work/evidence/recent-reports' || name === '/api/ai/assistant/partners/office/conversations' || name === '/api/ai/manus/conversations') data = [];
      else { unknown.push(method + ' ' + name); status = 404; }
      return route.fulfill({ status, json: data });
    }
    let file = path.join(dist, name);
    try { if (!(await fs.stat(file)).isFile()) throw Error(); } catch { file = path.join(dist, 'index.html'); }
    return route.fulfill({ body: await fs.readFile(file), contentType: ({ '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css' })[path.extname(file)] || 'application/octet-stream' });
  });
  return { context, page, state, finish, request, append };
}

const active = [];
try {
  const local = await fixture('local'); active.push(local); const { page, state } = local;
  await page.goto('http://localhost:19057/coding');
  await page.getByTestId('local-status').filter({ hasText: '已连接' }).waitFor();
  await page.getByTestId('coding-prompt').fill('请处理导航期间的候选');
  await page.getByTestId('coding-send').click();
  await until(() => state.results.some(item => item.operation === 'ping'), 'initial candidate handshake was not completed');
  const sessionId = state.session.id, turnId = state.turnId;
  await page.getByRole('link', { name: '新 Chat 对话', exact: true }).click();
  await page.waitForURL('**/assistant?mode=chat');
  await page.getByTestId('assistant-prompt').waitFor();
  check('active-local-task-can-navigate-to-chat');
  local.request('read', { path: 'main.txt' });
  await until(() => state.results.some(item => item.operation === 'read'), 'background local read stopped after leaving Coding');
  check('local-request-arriving-in-chat-still-runs', state.results.find(item => item.operation === 'read').path === '/assistant');
  local.request('read', { path: 'delayed-read.txt' });
  await page.waitForFunction(() => typeof window.__releaseNativeRead === 'function');
  await page.getByRole('link', { name: '设置 · 模型与连接', exact: true }).click();
  await page.waitForURL('**/settings');
  await page.evaluate(() => window.__releaseNativeRead());
  await until(() => state.results.filter(item => item.operation === 'read').length === 2, 'claimed native operation lost its receipt across navigation');
  check('in-flight-native-receipt-survives-navigation', state.results.filter(item => item.operation === 'read').at(-1).path === '/settings');
  await page.getByLabel('搜索模型', { exact: true }).focus();
  state.session.status = 'WAITING_APPROVAL';
  local.append('approval_required', { approvalId: 'background-approval', toolName: 'edit', arguments: { path: 'main.txt' } });
  const backgroundStarted = Date.now(), backgroundPolls = state.polls.filter(item => item.type === 'local').length;
  await pause(process.env.NAVIGATION_FAST === '1' ? 1600 : 32000);
  if (process.env.NAVIGATION_FAST !== '1') check('background-pump-survives-beyond-request-timeout', Date.now() - backgroundStarted >= 30000 && state.polls.filter(item => item.type === 'local').length >= backgroundPolls + 10);
  check('background-approval-does-not-steal-settings-focus', await page.getByLabel('搜索模型', { exact: true }).evaluate(element => element === document.activeElement));
  await page.screenshot({ path: path.join(out, 'settings-background.png') });
  state.session.status = 'RUNNING'; local.append('approval_resolved', { approvalId: 'background-approval', approved: true });
  for (const query of [{ session: 'other-session', project: 'other-project' }, { session: sessionId, transfer: 'unapproved-transfer' }]) {
    // Exercise an in-app deep link through the actual Router, without a page reload.
    console.log('NAVIGATING ' + JSON.stringify(query));
    await Promise.race([
      page.evaluate(async query => { await document.querySelector('#app').__vue_app__.config.globalProperties.$router.push({ path: '/coding', query }); return location.href; }, query),
      pause(8000).then(() => { throw new Error('Router push did not settle for ' + JSON.stringify(query) + '; current URL: ' + page.url()); }),
    ]);
    console.log('NAVIGATED ' + page.url());
    await page.waitForURL(url => url.pathname === '/coding' && url.searchParams.get('session') === sessionId && !url.searchParams.has('transfer') && !url.searchParams.has('project'));
    await page.getByTestId('coding-cancel').waitFor();
    check(query.transfer ? 'new-transfer-deep-link-preserves-active-local-task' : 'foreign-session-deep-link-preserves-active-local-task', state.sessionReads.every(id => id === sessionId));
    await page.getByRole('link', { name: '设置 · 模型与连接', exact: true }).click();
    await page.waitForURL('**/settings');
  }
  local.request('edit', { path: 'main.txt', oldText: 'before', newText: 'after' });
  await until(() => state.session.status === 'SUCCEEDED', 'background candidate edit stopped on Settings');
  check('candidate-completes-on-settings', state.results.find(item => item.operation === 'edit').path === '/settings');
  await page.getByTestId('workspace-coding').click();
  await page.getByText('离开工作台后候选仍完成', { exact: true }).waitFor();
  check('return-shows-original-task-result', new URL(page.url()).searchParams.get('session') === sessionId && state.turnId === turnId);
  check('navigation-never-cancels-or-resubmits', state.cancels === 0 && state.turns.length === 1);
  check('candidate-operations-execute-once', state.claims.length === 4 && new Set(state.claims).size === 4 && state.results.length === 4);
  await page.screenshot({ path: path.join(out, 'local-return.png') });

  // Same owner can leave another active turn; logout must end the cached runtime.
  await page.getByTestId('coding-prompt').fill('退出登录后不得继续执行');
  await page.getByTestId('coding-send').click();
  await until(() => state.results.filter(item => item.operation === 'ping').length === 2, 'second turn handshake missing');
  await page.getByRole('link', { name: '设置 · 模型与连接', exact: true }).click();
  await page.waitForURL('**/settings');
  await page.getByRole('button', { name: '退出', exact: true }).click();
  await page.waitForURL('**/login');
  await pause(200); const pollCount = state.polls.length, resultCount = state.results.length;
  local.request('read', { path: 'after-logout.txt' });
  await pause(2800);
  check('logout-stops-background-poll-and-execution', state.polls.length === pollCount && state.results.length === resultCount);
  await page.getByLabel('用户名', { exact: true }).fill('owner-b');
  await page.getByLabel('密码', { exact: true }).fill('fixture-password');
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await page.waitForURL('**/assistant?mode=chat');
  await page.getByTestId('workspace-coding').click();
  await page.getByTestId('coding-prompt').waitFor();
  check('new-owner-does-not-reuse-old-cached-task', !(await page.locator('body').innerText()).includes('离开工作台后候选仍完成') && !(await page.locator('body').innerText()).includes('退出登录后不得继续执行'));
  check('new-owner-does-not-execute-old-claimed-work', state.results.length === resultCount && state.turns.length === 2);

  const remote = await fixture('server'); active.push(remote);
  await remote.page.goto('http://localhost:19057/coding?session=server-session');
  await remote.page.getByTestId('coding-cancel').waitFor();
  await remote.page.getByRole('link', { name: '新 Chat 对话', exact: true }).click();
  await remote.page.waitForURL('**/assistant?mode=chat');
  const priorPolls = remote.state.polls.length;
  await until(() => remote.state.polls.length > priorPolls, 'server status polling stopped in Chat');
  check('server-task-keeps-status-connection-in-chat');
  await remote.page.getByRole('link', { name: '设置 · 模型与连接', exact: true }).click();
  await remote.page.waitForURL('**/settings');
  remote.finish('服务端任务在设置页期间完成');
  await remote.page.getByTestId('workspace-coding').click();
  await remote.page.getByText('服务端任务在设置页期间完成', { exact: true }).waitFor();
  check('server-return-recovers-same-session', new URL(remote.page.url()).searchParams.get('session') === 'server-session');
  check('server-task-never-restarted', remote.state.turns.length === 0 && remote.state.cancels === 0);

  const delayed = await fixture('local'); active.push(delayed);
  await delayed.page.goto('http://localhost:19057/coding');
  await delayed.page.getByTestId('local-status').filter({ hasText: '已连接' }).waitFor();
  delayed.state.pauseCreate = true; delayed.state.pauseTurn = true;
  await delayed.page.getByTestId('coding-prompt').fill('等待提交回执期间切换页面');
  await delayed.page.getByTestId('coding-send').click();
  await until(() => delayed.state.createStarted, 'create request did not start');
  await delayed.page.getByRole('link', { name: '新 Chat 对话', exact: true }).click();
  await delayed.page.waitForURL('**/assistant?mode=chat');
  delayed.state.releaseCreate();
  await until(() => delayed.state.turnStarted, 'background create response did not continue original submission');
  check('delayed-session-create-does-not-pull-user-back', new URL(delayed.page.url()).pathname === '/assistant');
  await delayed.page.getByRole('link', { name: '设置 · 模型与连接', exact: true }).click();
  await delayed.page.waitForURL('**/settings');
  delayed.state.releaseTurn();
  await until(() => delayed.state.results.some(item => item.operation === 'ping'), 'background submit response did not retain task worker');
  check('delayed-turn-response-keeps-current-route-and-one-submission', new URL(delayed.page.url()).pathname === '/settings' && delayed.state.turns.length === 1);
  // The sidebar bare /coding link must reopen the active task, not erase it.
  await delayed.page.getByRole('link', { name: '新编程任务', exact: true }).click();
  await delayed.page.waitForURL(url => url.pathname === '/coding' && url.searchParams.get('session') === delayed.state.session.id);
  await delayed.page.getByTestId('coding-cancel').waitFor();
  check('bare-coding-link-preserves-active-local-task', new URL(delayed.page.url()).searchParams.get('session') === delayed.state.session.id);
  delayed.finish('延迟回执任务正常完成');
  await delayed.page.getByText('延迟回执任务正常完成', { exact: true }).waitFor();
  check('delayed-submission-is-never-duplicated', delayed.state.cancels === 0 && delayed.state.turns.length === 1 && delayed.state.claims.length === 1);
  check('no-page-errors', errors.length === 0); check('no-unknown-api-calls', unknown.length === 0);
  await fs.writeFile(path.join(out, 'RESULT.json'), JSON.stringify({ status: 'PASSED', checks, errors, unknown, modelCalls: 0,
    scope: 'Actual Vue Router and built components; backend and native candidate APIs are scripted fixtures', localResults: state.results, localClaims: state.claims }, null, 2));
  console.log(JSON.stringify({ status: 'PASSED', checks: Object.keys(checks).length }));
} catch (error) {
  await fs.writeFile(path.join(out, 'FAILURE.json'), JSON.stringify({ error: String(error), checks, errors, unknown,
    pages: await Promise.all(active.map(async item => ({ url: item.page.url(), state: item.state, body: await item.page.locator('body').innerText({timeout:2000}).catch(error => String(error)) }))) }, null, 2));
  if (active[0]) await active[0].page.screenshot({ path: path.join(out, 'failure.png'), fullPage: true,timeout:2000 }).catch(() => {});
  throw error;
} finally { await browser.close(); }
