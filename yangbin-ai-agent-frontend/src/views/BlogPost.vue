<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter, onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router'
import { useUnsavedChanges } from '../composables/useUnsavedChanges'
import { api, errorMessage } from '../workspace-api'
import { workspaceOwner } from '../workspace-state'
import { downloadHexoPost } from '../blog-export'
import BlogMarkdown from '../components/BlogMarkdown.vue'
import StudioIcon from '../components/StudioIcon.vue'
import '../blog.css'

const route = useRoute(), router = useRouter()
const editing = computed(() => route.name !== 'blog-read')
const post = ref(null), title = ref(''), markdown = ref(''), tagsInput = ref('')
const busy = ref(false), loaded = ref(false), preview = ref(false), conflict = ref(false)
const notice = ref(''), error = ref(''), textArea = ref(null), creationId = ref('')
const baseRevision = ref(null), storageWarning = ref(false), imported = ref(false)
const fields = () => ({ title: title.value, markdown: markdown.value, tags: tagsInput.value.split(/[,，]/).map(tag => tag.trim()).filter(Boolean) })
const hasPublicPost = computed(() => !!post.value?.publishedAt)
const publicPath = computed(() => '/p/' + post.value?.id)
const publicUrl = computed(() => new URL(publicPath.value, location.origin).href)
const draftSlot = () => JSON.stringify(['blog-draft', workspaceOwner(), route.params.id || 'new'])
const url = id => '/ai/blog/posts/' + encodeURIComponent(id)
const words = computed(() => Array.from(markdown.value.trim()).length)
const unsaved = computed(() => loaded.value && editing.value && storageWarning.value && (title.value !== (post.value?.title || '') || markdown.value !== (post.value?.markdown || '') || JSON.stringify(fields().tags) !== JSON.stringify(post.value?.tags || [])))
const { allowDiscard } = useUnsavedChanges(unsaved, false, workspaceOwner)
onBeforeRouteLeave(() => allowDiscard())
onBeforeRouteUpdate(() => allowDiscard())
let generation = 0
onBeforeUnmount(() => { generation++ })

function clearDraft() {
  try { localStorage.removeItem(draftSlot()) } catch { /* storage unavailable */ }
}
function cacheDraft() {
  if (!loaded.value || !editing.value || !workspaceOwner()) return
  const saved = post.value
  if (title.value === (saved?.title || '') && markdown.value === (saved?.markdown || '')
    && tagsInput.value === (saved?.tags?.join('，') || '')) {
    clearDraft()
    return
  }
  try {
    localStorage.setItem(draftSlot(), JSON.stringify({ title: title.value, markdown: markdown.value,
      tagsInput: tagsInput.value, baseRevision: baseRevision.value, creationId: creationId.value }))
    storageWarning.value = false
  } catch { storageWarning.value = true }
}
watch([title, markdown, tagsInput], cacheDraft, { flush: 'sync' })
function loadFields(article) {
  title.value = article?.title || ''
  markdown.value = article?.markdown || ''
  tagsInput.value = article?.tags?.join('，') || ''
  baseRevision.value = article?.revision ?? null
}
async function load() {
  const token = ++generation
  loaded.value = false
  busy.value = false
  post.value = null
  preview.value = conflict.value = storageWarning.value = imported.value = false
  notice.value = error.value = ''
  loadFields(null)
  if (!workspaceOwner()) return
  try {
    if (route.params.id) {
      const { data } = await api.get(url(route.params.id))
      if (token !== generation) return
      post.value = data
      loadFields(data)
    }
    if (editing.value) {
      let draft
      try { draft = JSON.parse(localStorage.getItem(draftSlot())) } catch { /* optional local cache */ }
      creationId.value = typeof draft?.creationId === 'string' ? draft.creationId : ''
      if (draft && ['title', 'tagsInput', 'markdown'].every(key => typeof draft[key] === 'string')) {
        title.value = draft.title
        markdown.value = draft.markdown
        tagsInput.value = draft.tagsInput
        baseRevision.value = draft.baseRevision
        conflict.value = baseRevision.value !== (post.value?.revision ?? null)
        notice.value = '已恢复此设备上的草稿。'
      }
      if (history.state?.blogPublishFailed) {
        error.value = '草稿已保存，公开发布未完成。请核对后重试。'
        history.replaceState({ ...history.state, blogPublishFailed: false }, '')
      }
    }
    loaded.value = true
  } catch (e) {
    if (token === generation) error.value = errorMessage(e)
  }
}
watch([() => route.path, workspaceOwner], load, { immediate: true })

function restoreServer() {
  if (!window.confirm('用服务器上的文章替换此设备的修改？需要保留的内容可先导出 Markdown。')) return
  clearDraft()
  storageWarning.value = false
  if (!route.params.id && creationId.value) {
    router.replace('/blog/' + encodeURIComponent(creationId.value) + '/edit')
    return
  }
  load()
}
async function save(publish) {
  if (busy.value || conflict.value || !loaded.value) return
  busy.value = true
  error.value = notice.value = ''
  const token = generation
  try {
    const input = fields()
    let saved = post.value
    if (!saved) {
      if (!creationId.value) {
        // UUIDv4 for browsers without randomUUID (e.g. an HTTP LAN development origin).
        creationId.value = typeof crypto.randomUUID === 'function' ? crypto.randomUUID()
          : '10000000-1000-4000-8000-100000000000'.replace(/[018]/g, digit =>
            (Number(digit) ^ crypto.getRandomValues(new Uint8Array(1))[0] & (15 >> (Number(digit) / 4))).toString(16))
      }
      cacheDraft()
      saved = (await api.post('/ai/blog/posts', { ...input, id: creationId.value })).data
    } else if (input.title !== saved.title || input.markdown !== saved.markdown || JSON.stringify(input.tags) !== JSON.stringify(saved.tags)) {
      saved = (await api.put(url(saved.id), { ...input, revision: baseRevision.value })).data
    }
    if (token !== generation) return
    post.value = saved
    baseRevision.value = saved.revision
    storageWarning.value = false
    clearDraft()
    if (publish && saved.status !== 'PUBLISHED') {
      saved = (await api.post(url(saved.id) + '/publish', { revision: saved.revision })).data
      if (token !== generation) return
      post.value = saved
    }
    if (publish) await router.replace('/blog/' + saved.id)
    else if (!route.params.id) await router.replace('/blog/' + saved.id + '/edit')
    else notice.value = saved.status === 'PUBLISHED' ? '已是最新发布版本。' : '草稿已保存到账户。'
  } catch (e) {
    if (token !== generation) return
    if (e.response?.status === 409) conflict.value = true
    else error.value = errorMessage(e)
    // Creating the draft may have succeeded before the publish request failed.
    if (post.value && !route.params.id) {
      await router.replace({ path: '/blog/' + post.value.id + '/edit', state: { blogPublishFailed: true } })
    }
  } finally {
    if (token === generation) busy.value = false
  }
}
async function importKnowledge() {
  busy.value = true
  const token = generation
  error.value = notice.value = ''
  try {
    await api.post(url(post.value.id) + '/knowledge', { revision: post.value.revision })
    if (token === generation) imported.value = true
  } catch (e) {
    if (token === generation) error.value = e.response?.status === 409 ? '文章已更新，请刷新后再导入。' : errorMessage(e)
  } finally { if (token === generation) busy.value = false }
}
async function remove() {
  if (!window.confirm('删除这篇文章并撤下公开链接？')) return
  busy.value = true
  const token = generation
  try {
    await api.delete(url(post.value.id), { params: { revision: post.value.revision } })
    if (token === generation) { clearDraft(); await router.replace('/blog') }
  } catch (e) { if (token === generation) error.value = errorMessage(e) }
  finally { if (token === generation) busy.value = false }
}
async function copyLink() {
  try { await navigator.clipboard.writeText(publicUrl.value); notice.value = '公开链接已复制。' }
  catch { notice.value = '公开链接：' + publicUrl.value }
}
function insert(before, sample, after = '') {
  const field = textArea.value
  const start = field.selectionStart, end = field.selectionEnd
  const selected = markdown.value.slice(start, end) || sample
  markdown.value = markdown.value.slice(0, start) + before + selected + after + markdown.value.slice(end)
  nextTick(() => { field.focus(); field.setSelectionRange(start + before.length, start + before.length + selected.length) })
}
</script>
<template>
  <main class="blog">
    <nav class="blog-breadcrumb" aria-label="文章导航"><RouterLink to="/blog">我的博客</RouterLink><span>/</span>{{ editing ? (route.params.id ? '编辑文章' : '写文章') : '阅读文章' }}</nav>
    <p v-if="error" class="blog-error" role="alert">{{ error }} <button v-if="!loaded" @click="load">重试</button></p>
    <p v-if="notice" class="blog-notice" role="status">{{ notice }}</p>
    <p v-if="!loaded && !error" class="blog-empty">正在打开文章…</p>
    <template v-if="loaded && editing">
      <header class="blog-masthead">
        <div><h1>{{ post ? '编辑文章' : '写文章' }}</h1><p class="blog-meta">Markdown · {{ words }} 字</p></div>
        <div class="blog-actions">
          <button :disabled="busy" data-testid="blog-export" @click="downloadHexoPost({...post,...fields()})">导出 Markdown</button>
          <button :disabled="busy || conflict" data-testid="blog-save" @click="save(false)">保存草稿</button>
          <button class="blog-primary" :disabled="busy || conflict || !title.trim() || !markdown.trim()" data-testid="blog-publish" @click="save(true)">公开发布</button>
        </div>
      </header>
      <p v-if="storageWarning" class="blog-error" role="alert">当前浏览器无法保留草稿，请点击「保存草稿」保存到账户。</p>
      <div v-if="conflict" class="blog-error" role="alert">服务器上已有更新，此设备的修改已保留。请先导出副本，再核对服务器版本。
        <button @click="restoreServer">载入服务器版本</button>
      </div>
      <fieldset class="blog-editor" :disabled="busy" @keydown.ctrl.s.prevent="save(false)" @keydown.meta.s.prevent="save(false)">
        <label class="blog-field">文章标题<input v-model="title" maxlength="120" placeholder="输入文章标题" data-testid="blog-title"></label>
        <label class="blog-field">标签<input v-model="tagsInput" placeholder="多个标签用逗号分隔" data-testid="blog-tags"></label>
        <div class="blog-controls">
          <div class="blog-tabs"><button :aria-pressed="!preview" @click="preview=false">写作</button><button :aria-pressed="preview" data-testid="blog-preview" @click="preview=true">预览</button></div>
          <div v-if="!preview" class="blog-format" aria-label="Markdown 格式">
            <button title="二级标题" aria-label="插入二级标题" @click="insert('## ', '小标题')">H2</button>
            <button title="粗体" aria-label="插入粗体" @click="insert('**', '文字', '**')"><b>B</b></button>
            <button title="链接" aria-label="插入链接" @click="insert('[', '链接文字', '](https://)')"><StudioIcon name="plug" :size="15"/></button>
          </div>
        </div>
        <textarea v-if="!preview" ref="textArea" v-model="markdown" aria-label="Markdown 正文" maxlength="60000" placeholder="开始写正文…" data-testid="blog-body"></textarea>
        <article v-else class="blog-preview"><h2>{{ title || '无标题草稿' }}</h2><BlogMarkdown :text="markdown"/></article>
      </fieldset>
      <p class="blog-meta blog-tip">保存草稿后，可在其他设备继续编辑。公开发布后，任何持有链接的人都可以阅读。</p>
    </template>
    <template v-if="loaded && !editing && post">
      <article class="blog-paper">
        <p class="blog-meta">{{ post.status === 'PUBLISHED' ? '已发布' : '草稿' }} · 更新于 {{ new Date(post.updatedAt).toLocaleDateString('zh-CN') }}</p>
        <h1>{{ post.title || '无标题草稿' }}</h1>
        <div class="blog-tags"><span v-for="tag in post.tags" :key="tag">{{ tag }}</span></div>
        <BlogMarkdown :text="post.markdown"/>
      </article>
      <footer class="blog-actions blog-reader-actions">
        <RouterLink class="blog-primary" :to="'/blog/'+post.id+'/edit'" data-testid="blog-edit"><StudioIcon name="pen" :size="16"/>编辑文章</RouterLink>
        <button v-if="post.status === 'PUBLISHED'" :disabled="busy || imported" data-testid="blog-import" @click="importKnowledge">{{ imported ? '已导入知识库' : '导入知识库' }}</button>
        <RouterLink v-if="imported" to="/knowledge">去知识库查询</RouterLink>
        <a v-if="hasPublicPost" :href="publicPath" target="_blank" rel="noopener" data-testid="blog-public">打开公开文章</a>
        <button v-if="hasPublicPost" data-testid="blog-share" @click="copyLink">复制公开链接</button>
        <button :disabled="busy" data-testid="blog-delete" @click="remove">删除文章</button>
      </footer>
    </template>
  </main>
</template>
