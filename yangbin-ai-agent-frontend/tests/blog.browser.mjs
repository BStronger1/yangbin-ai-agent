import assert from 'node:assert/strict';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const frontend = fileURLToPath(new URL('../', import.meta.url));
const dist = path.join(frontend, 'dist');
const out = path.resolve(process.env.EVIDENCE_DIR || path.join(frontend, 'tmp/blog-ui'));
const origin = 'https://blog-fixture.test';
const apiPath = '/api/ai/blog/posts';
const checks = [];
const pageErrors = [];
const unknownRequests = [];
let passed = false;
const posts = new Map();
const published = new Map();
const indexed = new Map();
const conflicts = [];
let rejectImport = false;

function summary({markdown, ...post}) {
  return post;
}

async function handleApi(route, url) {
  const request = route.request();
  const method = request.method();
  const pathname = url.pathname;
  const reply = (json, status = 200) => route.fulfill({json, status});
  if (method === 'GET' && pathname === '/api/auth/me') {
    return reply({user: {id: 'blog-user', username: '作者', role: 'user', csrf: 'test-csrf'}});
  }
  if (method !== 'GET' && request.headers()['x-csrf-token'] !== 'test-csrf') {
    return reply({message: '缺少有效的会话校验'}, 403);
  }
  if (pathname === '/api/ai/knowledge' && method === 'GET') return reply([...indexed.values()]);
  if (pathname === '/api/ai/rag/search' && method === 'GET') {
    const term = url.searchParams.get('query');
    return reply([...indexed.values()].filter(d => term && d.text.includes(term))
      .map(d => ({source: d.filename, score: 1, content: d.text})));
  }
  if (pathname === apiPath && method === 'GET') return reply([...posts.values()].map(summary));
  const input = request.postData() ? request.postDataJSON() : {};
  if (pathname === apiPath && method === 'POST') {
    const now = new Date().toISOString();
    const post = {id: input.id || crypto.randomUUID(), title: input.title, markdown: input.markdown,
      tags: input.tags, status: 'DRAFT', revision: 1, createdAt: now, updatedAt: now, publishedAt: null};
    posts.set(post.id, post);
    return reply(post, 201);
  }
  if (pathname.startsWith(apiPath + '/')) {
    const [id, action] = pathname.slice(apiPath.length + 1).split('/');
    const post = posts.get(id);
    if (!post) return reply({message: '文章不存在'}, 404);
    if (!action && method === 'GET') return reply(post);
    const revision = method === 'DELETE' ? Number(url.searchParams.get('revision')) : input.revision;
    if (revision !== post.revision) {
      conflicts.push({id, revision, current: post.revision});
      return reply({message: '文章版本冲突，请刷新后重试'}, 409);
    }
    if (!action && method === 'PUT') {
      Object.assign(post, {title: input.title, markdown: input.markdown, tags: input.tags,
        status: 'DRAFT', revision: post.revision + 1, updatedAt: new Date().toISOString()});
      return reply(post);
    }
    if (action === 'publish' && method === 'POST') {
      Object.assign(post, {status: 'PUBLISHED', publishedAt: new Date().toISOString(), revision: post.revision + 1});
      const {id, title, markdown, tags, publishedAt} = post;
      published.set(id, {id, title, markdown, tags: [...tags], publishedAt});
      return reply(post);
    }
    if (action === 'knowledge' && method === 'POST') {
      if (post.status !== 'PUBLISHED') return reply({message: '请先发布文章'}, 409);
      if (rejectImport) return reply({message: '索引暂时不可用'}, 503);
      const documentId = 'blog-' + post.id;
      indexed.set(documentId, {id: documentId, filename: post.title + '.md', text: post.markdown, state: 'READY', chunks: 1});
      return reply({documentId, revision: post.revision});
    }
    if (!action && method === 'DELETE') {
      posts.delete(id);
      published.delete(id);
      return route.fulfill({status: 204});
    }
  }
  unknownRequests.push(method + ' ' + pathname);
  return reply({message: '没有登记的测试接口'}, 404);
}

const contentType = {'.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.woff2': 'font/woff2', '.svg': 'image/svg+xml', '.png': 'image/png'};
async function installFixture(context, authenticated = true) {
  await context.route('**/*', async route => {
    const url = new URL(route.request().url());
    if (url.origin !== origin) {
      unknownRequests.push(url.origin);
      return route.abort();
    }
    if (url.pathname === '/fixture.png') return route.fulfill({
      contentType: 'image/png',
      body: Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aZ1cAAAAASUVORK5CYII=', 'base64')
    });
    if (url.pathname.startsWith('/api/open/blog/')) {
      const article = published.get(url.pathname.split('/').at(-1));
      if (!article) return route.fulfill({status: 404, json: {message: '文章不存在'}});
      return route.fulfill({json: article});
    }
    if (url.pathname.startsWith('/api/')) {
      if (!authenticated) {
        if (url.pathname === '/api/auth/me') return route.fulfill({json: {user: null}});
        unknownRequests.push('匿名页面请求私有接口 ' + url.pathname);
        return route.fulfill({status: 401, json: {message: '请先登录'}});
      }
      return handleApi(route, url);
    }
    const name = url.pathname.startsWith('/assets/') ? url.pathname.slice(1) : 'index.html';
    const body = await readFile(path.join(dist, name));
    await route.fulfill({body, contentType: contentType[path.extname(name)] || 'application/octet-stream'});
  });
  const page = await context.newPage();
  page.setDefaultTimeout(10000);
  page.on('pageerror', error => pageErrors.push(error.message));
  return page;
}

const title = '移动写作回归';
const markdown = [
  '## 合欢树观察', '现场编号 **HEHUAN-2907**', '![树叶照片](' + origin + '/fixture.png)',
  '<script>window.blogInjected=true</script>',
  '<img alt="无效图片" src="http://unsafe.invalid/example.png" onerror="window.blogInjected=true">',
  '<a href="javascript:alert(1)">危险地址</a>'
].join('\n\n');
const edited = markdown + '\n\n手机补记：观察到一片新叶。';
const unsaved = markdown + '\n\n桌面本地尚未同步的改动。';
await mkdir(out, {recursive: true});
const browser = await chromium.launch({headless: true, ...(process.env.CHROME_EXECUTABLE_PATH
  ? {executablePath: process.env.CHROME_EXECUTABLE_PATH} : {})});
try {
  const desktop = await installFixture(await browser.newContext({viewport: {width: 1440, height: 900}}));
  const phone = await installFixture(await browser.newContext({viewport: {width: 390, height: 844}, isMobile: true, hasTouch: true}));
  const reader = await installFixture(await browser.newContext(), false);
  const at = (page, name) => page.getByTestId('blog-' + name);
  await desktop.goto(origin + '/blog');
  await at(desktop, 'new').click();
  await at(desktop, 'title').fill(title);
  await at(desktop, 'body').fill(markdown);
  await desktop.reload();
  await desktop.waitForFunction(text => document.querySelector('[data-testid="blog-body"]')?.value === text, markdown);
  assert.equal(await at(desktop, 'title').inputValue(), title);
  assert.equal(posts.size, 0);
  checks.push('未提交的本机草稿在刷新后恢复');
  await at(desktop, 'preview').click();
  await desktop.getByRole('heading', {name: '合欢树观察'}).waitFor();
  assert.equal(await desktop.evaluate(() => Boolean(window.blogInjected)), false);
  const rendered = desktop.locator('.blog-markdown');
  assert.equal(await rendered.locator('script,[onerror],a[href^="javascript:"]').count(), 0);
  assert.equal(await rendered.getByAltText('无效图片').getAttribute('src'), null);
  assert.equal(await rendered.getByAltText('树叶照片').getAttribute('src'), origin + '/fixture.png');
  await desktop.screenshot({path: path.join(out, 'desktop-preview.png')});
  checks.push('Markdown 预览与脚本过滤');
  await at(desktop, 'save').click();
  await desktop.waitForURL(url => posts.size === 1 && url.pathname.includes([...posts.keys()][0]));
  const post = [...posts.values()][0];
  assert.equal(post.markdown, markdown);
  assert.equal(post.status, 'DRAFT');
  checks.push('保存服务端草稿');
  await reader.goto(origin + '/p/' + post.id);
  await reader.getByRole('alert').waitFor();
  assert.equal(await reader.getByText('HEHUAN-2907', {exact: true}).count(), 0);
  checks.push('匿名读者无法打开草稿');
  await desktop.goto(origin + '/blog/' + post.id + '/edit');
  await at(desktop, 'body').fill(unsaved);

  await phone.goto(origin + '/blog');
  await phone.getByText(title, {exact: true}).first().click();
  await at(phone, 'edit').click();
  assert.equal(await at(phone, 'body').inputValue(), markdown);
  await at(phone, 'body').fill(edited);
  const fits = await phone.evaluate(() => document.documentElement.scrollWidth <= innerWidth);
  assert.ok(fits, '手机写作页不应水平溢出');
  const box = await at(phone, 'body').boundingBox();
  assert.ok(box && box.x >= 0 && box.x + box.width <= 390);
  await phone.screenshot({path: path.join(out, 'mobile-editor.png')});
  await at(phone, 'publish').click();
  await phone.waitForURL('**/blog/' + post.id);
  assert.equal(post.status, 'PUBLISHED');
  assert.equal(post.markdown, edited);
  await phone.getByText('手机补记：观察到一片新叶。', {exact: true}).waitFor();
  checks.push('独立手机上下文读取服务端草稿、编辑并发布');
  const publicLink = at(phone, 'public');
  assert.equal(new URL(await publicLink.getAttribute('href'), origin).pathname, '/p/' + post.id);
  await reader.reload();
  await reader.getByText('手机补记：观察到一片新叶。', {exact: true}).waitFor();
  assert.equal(await reader.getByRole('heading', {name: title}).count(), 1);
  assert.equal(await at(reader, 'edit').count(), 0);
  assert.equal(await at(reader, 'import').count(), 0);
  checks.push('无登录用户打开公开链接并只读浏览');
  await reader.screenshot({path: path.join(out, 'public-reader.png')});
  await at(phone, 'edit').click();
  const downloadReady = phone.waitForEvent('download');
  await at(phone, 'export').click();
  const download = await downloadReady;
  assert.match(download.suggestedFilename(), /\.md$/i);
  const markdownExport = await readFile(await download.path(), 'utf8');
  assert.match(markdownExport, /^---\r?\n/);
  assert.ok(markdownExport.includes(title) && markdownExport.includes(edited));
  checks.push('下载含 Hexo front matter 及完整正文的 Markdown');
  await phone.goto(origin + '/blog/' + post.id);

  await at(desktop, 'save').click();
  await desktop.getByRole('alert').waitFor();
  assert.ok(conflicts.length);
  assert.equal(await at(desktop, 'body').inputValue(), unsaved);
  assert.equal(post.markdown, edited);
  checks.push('过期客户端接收冲突并保留本地输入');

  rejectImport = true;
  await at(phone, 'import').click();
  await phone.getByRole('alert').waitFor();
  assert.equal(indexed.size, 0);
  rejectImport = false;
  await Promise.all([
    phone.waitForResponse(response => response.url().endsWith('/knowledge') && response.request().method() === 'POST' && response.ok()),
    at(phone, 'import').click()
  ]);
  assert.equal(indexed.size, 1);
  checks.push('索引失败可重试、成功后导入发布版本');
  await phone.getByRole('link', {name: '去知识库查询'}).click();
  await phone.getByLabel('输入一个问题').fill('HEHUAN-2907');
  await phone.getByRole('button', {name: '检索知识库'}).click();
  await phone.locator('.result').filter({hasText: '手机补记：观察到一片新叶。'}).waitFor();
  checks.push('知识库页面可显示已导入文章的检索结果');
  assert.deepEqual(pageErrors, []);
  assert.deepEqual(unknownRequests, []);
  passed = true;
} finally {
  await writeFile(path.join(out, 'report.json'), JSON.stringify({scope: '离线浏览器 API fixture', passed, checks, pageErrors, unknownRequests}, null, 2));
  await browser.close();
}
console.log(JSON.stringify(checks));
