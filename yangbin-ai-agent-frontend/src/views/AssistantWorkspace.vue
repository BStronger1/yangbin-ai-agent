<script setup>
import { ref, computed, watch, onBeforeUnmount, nextTick } from 'vue'
import { useRoute, useRouter, onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router'
import { useOverlay, isComposing } from '../composables/useOverlay'
import { useUnsavedChanges } from '../composables/useUnsavedChanges'
import { auth } from '../auth'
import { api, errorMessage } from '../workspace-api'
import { readWorkspace, writeWorkspace } from '../workspace-state'
import { mergeWorkspaceTurns } from '../workspace-projection'
import ReplyMarkdown from '../components/ReplyMarkdown.vue'
import PartnerModelPicker from '../components/PartnerModelPicker.vue'
import StudioIcon from '../components/StudioIcon.vue'
import '../coding-workspace.css'
import '../assistant-workspace.css'

const reasoningEffort = ref('')
const route = useRoute(), router = useRouter()
const mode = computed(() => route.query.mode === 'work' ? 'work' : 'chat')
const partner = computed(() => typeof route.query.partner === 'string' ? route.query.partner : 'office')
const space = computed(() => `assistant-${mode.value}:${partner.value}`)
const base = computed(() => '/ai/assistant/partners/' + encodeURIComponent(partner.value))
const resourceBase = computed(() => '/ai/work/partners/' + encodeURIComponent(partner.value))
const selected = ref(''), conversation = ref(null), sessions = ref([]), catalog = ref({ partners: [], skills: [] })
const draft = ref(''), attachments = ref([]), skill = ref('auto'), pending = ref(null), model = ref(''), modelReady = ref(false)
const goal = ref(''), deliverable = ref(''), constraints = ref(''), confirmed = ref(false)
const historyOpen = ref(false), resultPanel = ref(true), busy = ref(false), loading = ref(false), uploading = ref(false), error = ref(''), connection = ref(''), handoff = ref(false), handoffText = ref(''), preview = ref(null), showBrief = ref(true)
const handoffPanel = ref(null), historyPanel = ref(null), previewPanel = ref(null), handoffBaseline = ref('')
const {allowDiscard:allowHandoffDiscard} = useUnsavedChanges(computed(() => handoff.value && handoffText.value !== handoffBaseline.value), false, () => auth.user?.id || auth.user?.username)
async function closeHandoff() { if(await allowHandoffDiscard()) handoff.value = false }
useOverlay(handoff, handoffPanel, closeHandoff)
useOverlay(historyOpen, historyPanel, () => { historyOpen.value = false }, {modal:false})
useOverlay(preview, previewPanel, () => { preview.value = null })
onBeforeRouteLeave(() => allowHandoffDiscard())
onBeforeRouteUpdate(() => allowHandoffDiscard())
function composeKeys(event) { if (event.key === 'Enter' && (event.ctrlKey || event.metaKey) && !isComposing(event)) { event.preventDefault(); send() } }
const scroll = ref(null), fileInput = ref(null), nextBefore = ref(null), older = ref(false)
let epoch = 0, alive = true, restoring = false, source = null, poll = null, controller = null, stateContext = null
const running = computed(() => ['RUNNING', 'CANCELLING'].includes(conversation.value?.turns?.at(-1)?.status))
const taskResources = computed(() => (conversation.value?.turns || []).flatMap(t => t.artifacts || []))
const latestAnswer = computed(() => [...(conversation.value?.turns || [])].reverse().find(t => t.answer)?.answer || '')
const textSkills = computed(() => catalog.value.skills.filter(s => s.kind === 'TEXT'))
const status = value => ({ RUNNING: '执行中', CANCELLING: '正在停止', SUCCEEDED: '已完成', FAILED: '未完成', CANCELLED: '已停止', INTERRUPTED: '已中断' }[value] || value || '准备就绪')
function uuid() { const bytes = crypto.getRandomValues(new Uint8Array(16)); bytes[6] = (bytes[6] & 15) | 64; bytes[8] = (bytes[8] & 63) | 128; return [...bytes].map((value, index) => ([4, 6, 8, 10].includes(index) ? '-' : '') + value.toString(16).padStart(2, '0')).join('') }
const draftKey = () => 'draft:' + (selected.value || 'new')
function save() {
  if (restoring || !stateContext) return
  writeWorkspace(stateContext.space, draftKey(), { text: draft.value, attachments: attachments.value, skill: skill.value, goal: goal.value, deliverable: deliverable.value, constraints: constraints.value }, stateContext.owner)
  writeWorkspace(stateContext.space, 'layout', { results: resultPanel.value }, stateContext.owner)
}
function restore() {
  const value = readWorkspace(space.value, draftKey(), {})
  restoring = true
  draft.value = value.text || ''; attachments.value = value.attachments || []; skill.value = value.skill || 'auto'
  goal.value = value.goal || ''; deliverable.value = value.deliverable || ''; constraints.value = value.constraints || ''; confirmed.value = false
  resultPanel.value = readWorkspace(space.value, 'layout', { results: true }).results !== false
  restoring = false
}
function detach() { source?.close(); source = null; clearTimeout(poll); poll = null }
function contentUrl(resource, inline = false) { return '/api' + resourceBase.value + '/resources/' + encodeURIComponent(resource.id) + '/content' + (inline ? '?preview=true' : '') }
function url(id = '', nextMode = mode.value, nextPartner = partner.value) { return { path: '/assistant', query: { mode: nextMode, ...(nextPartner !== 'office' ? { partner: nextPartner } : {}), ...(id ? { conversation: id } : {}) } } }
async function goMode(nextMode) {
  save()
  const last = readWorkspace(`assistant-${nextMode}:${partner.value}`, 'last')
  await router.push(url(last?.conversation || '', nextMode))
}
async function newTask() { save(); if (!selected.value) { restoring = true; draft.value = ''; attachments.value = []; goal.value = ''; deliverable.value = ''; constraints.value = ''; confirmed.value = false; restoring = false; save() } else await router.push(url()) }
function openTask(item) { save(); historyOpen.value = false; router.push(url(item.id)) }
async function bottom() { await nextTick(); if (scroll.value) scroll.value.scrollTop = scroll.value.scrollHeight }
function apply(data, replace = false) {
  if (data.id !== selected.value || data.partnerId !== partner.value || data.mode !== mode.value) throw new Error('任务归属不匹配，请从历史重新打开。')
  const follow = !scroll.value || scroll.value.scrollHeight - scroll.value.scrollTop - scroll.value.clientHeight < 150
  const previous = conversation.value
  if (previous && data.revision < previous.revision) return
  if (replace || !previous) { conversation.value = data; nextBefore.value = data.nextBefore ?? null }
  else conversation.value = { ...data, total: Math.max(previous.total || 0, data.total || 0), turns: mergeWorkspaceTurns(previous.turns, data.turns) }
  if (pending.value && data.turns.some(t => t.id === pending.value.requestId)) { pending.value = null; writeWorkspace(space.value, 'pending:' + data.id, null); draft.value = ''; attachments.value = []; confirmed.value = false }
  const brief = data.turns.at(-1)?.brief
  if (brief && !goal.value) { goal.value = brief.goal || ''; deliverable.value = brief.deliverable || ''; constraints.value = brief.constraints || '' }
  if (follow) bottom()
}
async function history(ticket = epoch) { const { data } = await api.get(base.value + '/conversations', { params: { mode: mode.value }, signal: controller.signal }); if (ticket === epoch) sessions.value = data }
async function refresh() {
  if (!selected.value || !alive) return
  const ticket = epoch
  try {
    const { data } = await api.get(base.value + '/conversations/' + selected.value + '/page', { params: { mode: mode.value }, signal: controller.signal })
    if (ticket !== epoch) return
    apply(data); connection.value = ''; error.value = ''
    if (!running.value) { detach(); await history(ticket) } else if (!source) subscribe(ticket)
  } catch (e) { if (ticket === epoch && e.code !== 'ERR_CANCELED') connection.value = '连接暂时中断，正在恢复服务端状态；不会重新提交。' }
  finally { if (ticket === epoch && (running.value || pending.value)) { clearTimeout(poll); poll = setTimeout(refresh, 2500) } }
}
function subscribe(ticket = epoch) {
  if (ticket !== epoch || !alive || source || !running.value) return
  const stream = new EventSource('/api' + base.value + '/conversations/' + selected.value + '/events?mode=' + mode.value)
  source = stream
  stream.addEventListener('state', event => {
    if (ticket !== epoch || source !== stream) return
    try { apply(JSON.parse(event.data)); connection.value = ''; if (!running.value) { detach(); history(ticket).catch(() => {}) } } catch { connection.value = '状态数据无法读取，正在重新核对。'; stream.close(); source = null; clearTimeout(poll); poll = setTimeout(refresh, 1200) }
  })
  stream.addEventListener('done', () => { if (source === stream) { stream.close(); source = null; refresh() } })
  stream.onerror = () => { if (source !== stream || ticket !== epoch) return; stream.close(); source = null; connection.value = '连接暂时中断，正在恢复服务端状态；不会重新提交。'; clearTimeout(poll); poll = setTimeout(refresh, 1200) }
}
async function load() {
  save(); const ticket = ++epoch; detach(); controller?.abort(); controller = new AbortController(); restoring = true
  const nextOwner = auth.user?.id || auth.user?.username
  const changedSpace = stateContext?.space !== space.value || stateContext?.owner !== nextOwner
  stateContext = { space: space.value, owner: nextOwner }
  selected.value = typeof route.query.conversation === 'string' ? route.query.conversation : ''
  conversation.value = null; sessions.value = []; pending.value = null; if (changedSpace) { model.value = ''; modelReady.value = false } loading.value = true; busy.value = false; error.value = ''; connection.value = ''; historyOpen.value = false; preview.value = null; handoff.value = false; nextBefore.value = null
  restoring = false; restore()
  writeWorkspace(space.value, 'last', selected.value ? { conversation: selected.value } : null)
  writeWorkspace('assistant-' + mode.value, 'last', selected.value ? { conversation: selected.value, partner: partner.value } : { partner: partner.value })
  writeWorkspace('assistant', 'mode', mode.value)
  if (selected.value) pending.value = readWorkspace(space.value, 'pending:' + selected.value)
  try {
    await Promise.all([history(ticket), api.get('/ai/work/catalog', { signal: controller.signal }).then(r => { if (ticket === epoch) catalog.value = r.data })])
    if (selected.value) { const { data } = await api.get(base.value + '/conversations/' + selected.value + '/page', { params: { mode: mode.value }, signal: controller.signal }); if (ticket === epoch) { apply(data, true); subscribe(ticket); if (running.value || pending.value) poll = setTimeout(refresh, 2500) } }
  } catch (e) { if (ticket === epoch && e.code !== 'ERR_CANCELED') error.value = pending.value ? '上一条消息状态尚未确认。请刷新记录，或重试同一条消息。' : errorMessage(e) }
  finally { if (ticket === epoch) { loading.value = false; bottom() } }
}
async function send(retry = false) {
  if (busy.value || running.value || loading.value || uploading.value || !modelReady.value || (!retry && (!draft.value.trim() || pending.value))) return
  if (!retry && mode.value === 'work' && (!goal.value.trim() || !deliverable.value.trim() || !confirmed.value)) { error.value = '请填写工作目标和交付成果，并确认本轮执行范围。'; showBrief.value = true; return }
  const ticket = epoch, submissionBase = base.value, submissionOwner = auth.user?.id || auth.user?.username; let input = retry ? pending.value : null
  if (!input) input = { conversationId: selected.value || uuid(), requestId: uuid(), expectedRevision: conversation.value?.revision || 0, text: draft.value.trim(), skillId: mode.value === 'chat' ? 'auto' : skill.value, attachmentIds: attachments.value.map(r => r.id), reasoningEffort: reasoningEffort.value || null, modelProfileId: model.value || null, mode: mode.value, brief: mode.value === 'work' ? { goal: goal.value.trim(), deliverable: deliverable.value.trim(), constraints: constraints.value.trim() } : null, confirmed: mode.value === 'work' && confirmed.value }
  busy.value = true; error.value = ''; pending.value = input
  writeWorkspace(space.value, 'pending:' + input.conversationId, input)
  if (!selected.value) {
    // Update the route without triggering a second submit or clearing the pending identity.
    writeWorkspace(space.value, 'draft:new', null); selected.value = input.conversationId; save(); writeWorkspace(space.value, 'last', { conversation: selected.value }); writeWorkspace('assistant-' + mode.value, 'last', { conversation: selected.value, partner: partner.value }); await router.replace(url(selected.value))
  }
  try {
    if (submissionOwner !== (auth.user?.id || auth.user?.username)) return
    const { data } = await api.post(submissionBase + '/jobs', input, { params: { mode: input.mode } })
    if (ticket !== epoch) return
    apply(data, true); await history(ticket); if (ticket !== epoch) return; subscribe(ticket); if (running.value) poll = setTimeout(refresh, 2500)
  } catch (e) {
    if (ticket !== epoch || e.code === 'ERR_CANCELED') return
    if (e.response?.status >= 400 && e.response.status < 500) { pending.value = null; writeWorkspace(space.value, 'pending:' + input.conversationId, null); draft.value = input.text; confirmed.value = false; error.value = errorMessage(e) }
    else { error.value = '提交结果尚未确认。先刷新记录；重试会沿用原请求编号。'; poll = setTimeout(refresh, 1500) }
  } finally { if (ticket === epoch) busy.value = false }
}
async function stop() {
  if (!selected.value || busy.value) return
  busy.value = true; const ticket = epoch
  try { const { data } = await api.post(base.value + '/conversations/' + selected.value + '/stop', {}, { params: { mode: mode.value }, signal: controller.signal }); if (ticket === epoch) { apply(data); connection.value = '已请求停止，正在核对执行状态。'; refresh() } } catch (e) { if (ticket === epoch) error.value = errorMessage(e) } finally { if (ticket === epoch) busy.value = false }
}
async function upload(event) {
  const chosen = [...event.target.files]; event.target.value = ''
  if (chosen.length + attachments.value.length > 3) { error.value = '每轮最多附加 3 个文件。'; return }
  const ticket = epoch; uploading.value = true
  try { for (const file of chosen) { if (file.size > 1024 * 1024) throw new Error('每个文件最多 1 MB。'); const form = new FormData(); form.append('file', file); const { data } = await api.post(resourceBase.value + '/files', form, { signal: controller.signal }); if (ticket !== epoch) return; attachments.value.push(data) } } catch (e) { if (ticket === epoch) error.value = e.response ? errorMessage(e) : e.message } finally { if (ticket === epoch) uploading.value = false }
}
async function loadOlder() {
  if (older.value || nextBefore.value === null) return
  older.value = true; const ticket = epoch
  try { const { data } = await api.get(base.value + '/conversations/' + selected.value + '/page', { params: { mode: mode.value, before: nextBefore.value }, signal: controller.signal }); if (ticket !== epoch) return; if (data.mode !== mode.value || data.id !== selected.value) throw new Error('历史归属不匹配'); conversation.value.turns = [...data.turns, ...conversation.value.turns]; nextBefore.value = data.nextBefore ?? null } catch (e) { if (ticket === epoch) error.value = errorMessage(e) } finally { if (ticket === epoch) older.value = false }
}
function downloadText(turn) { const object = URL.createObjectURL(new Blob([turn.answer], { type: 'text/markdown;charset=utf-8' })); const a = document.createElement('a'); a.href = object; a.download = 'yangbin-' + turn.id + '.md'; a.click(); setTimeout(() => URL.revokeObjectURL(object), 1000) }
function prepareHandoff() { handoffText.value = draft.value || latestAnswer.value; handoffBaseline.value = handoffText.value; handoff.value = true }
async function transfer() { if (!handoffText.value.trim()) return; const old = readWorkspace('coding', 'draft:new', ''); const merged = [old, '来自通用助手的明确转交：\n' + handoffText.value.trim()].filter(Boolean).join('\n\n'); if (merged.length > 16000) { error.value = 'Coding 草稿与转交内容合计超过 16000 字，请缩短内容后再转交。'; return } writeWorkspace('coding', 'draft:new', merged); handoff.value = false; await router.push('/coding') }
watch([draft, attachments, skill, goal, deliverable, constraints, resultPanel], save, { deep: true, flush: 'sync' })
watch([goal, deliverable, constraints, draft, skill, attachments], () => { confirmed.value = false }, { deep: true, flush: 'sync' })
watch(() => [mode.value, partner.value, route.query.conversation, auth.user?.id], (_, previous) => { if (previous && previous[0] === mode.value && previous[1] === partner.value && (route.query.conversation || '') === selected.value && previous[3] === auth.user?.id) return; load() }, { immediate: true })
onBeforeUnmount(() => { save(); alive = false; epoch++; detach(); controller?.abort() })
</script>

<template>
  <main class="coding-workspace assistant-workspace" data-testid="assistant-workspace">
    <header class="coding-topbar"><div class="coding-location"><StudioIcon name="spark" :size="20"/><strong>通用助手</strong><div class="assistant-modes" aria-label="通用助手模式"><button :class="{active:mode==='chat'}" :aria-pressed="mode==='chat'" data-testid="assistant-mode-chat" @click="goMode('chat')">Chat</button><button :class="{active:mode==='work'}" :aria-pressed="mode==='work'" data-testid="assistant-mode-work" @click="goMode('work')">Work</button></div></div><div class="coding-top-actions"><button class="quiet-button" data-testid="assistant-history" @click="historyOpen=!historyOpen;history().catch(e=>error=errorMessage(e))"><StudioIcon name="clock" :size="18"/><span>历史记录</span></button><button v-if="mode==='work'" class="quiet-button" @click="resultPanel=!resultPanel" aria-label="成果面板"><StudioIcon name="document" :size="18"/><span>成果</span></button><button class="quiet-button" @click="newTask" aria-label="新任务"><StudioIcon name="plus" :size="18"/><span>新任务</span></button></div></header>
    <div v-if="historyOpen" ref="historyPanel" role="dialog" aria-label="助手历史" class="coding-history-panel"><div class="panel-heading"><h2>{{mode==='work'?'Work 任务':'Chat 对话'}}</h2><button class="icon-button" aria-label="关闭历史" @click="historyOpen=false">×</button></div><button v-for="item in sessions" :key="item.id" class="history-row" @click="openTask(item)"><strong>{{item.title}}</strong><span>{{status(item.status)}}</span></button><p v-if="!sessions.length" class="muted">这里还没有记录。</p><RouterLink to="/super-agent" class="assistant-legacy">打开旧版智能体对话历史 →</RouterLink></div>
    <div class="coding-body">
      <section ref="scroll" class="coding-conversation" aria-label="助手任务内容">
        <div v-if="!selected" class="coding-welcome assistant-welcome"><div class="assistant-emblem" aria-hidden="true">✦</div><h1>{{mode==='chat'?'一个问题，一起想清楚':'把目标变成可审阅的成果'}}</h1><p class="coding-mode-description">{{mode==='chat'?'问答、学习、讨论与内容修改，先从直接回答开始。':'说明目标与交付要求，整理材料，查看过程，再继续修改成果。'}}</p><div class="assistant-starters"><button v-if="mode==='chat'" @click="draft='请用一个简单例子解释这个概念：'">解释一个概念</button><button v-if="mode==='chat'" @click="draft='帮我修改下面这段文字，保留原意：'">一起修改内容</button><button v-if="mode==='work'" @click="goal='整理现有材料并形成研究结论';deliverable='一份带证据与局限的研究报告';draft='请根据我提供的材料整理报告，先指出需要补充的信息。'">整理研究报告</button><button v-if="mode==='work'" @click="goal='梳理项目进展与下一步';deliverable='可审阅的项目周报文档';draft='请根据下面材料整理项目周报：'">交付项目周报</button></div><div class="assistant-resources"><RouterLink to="/skills?tab=partners">工作伙伴</RouterLink><RouterLink to="/knowledge">知识库</RouterLink><RouterLink to="/mcp">MCP 工具</RouterLink><RouterLink v-if="mode==='work'" to="/presentation">PPT 工作台</RouterLink></div></div>
        <div v-else class="coding-thread"><div class="thread-heading"><h1>{{conversation?.title || '正在核对任务'}}</h1><span class="status-chip" :class="{running}" role="status">{{pending?'待确认':status(conversation?.turns?.at(-1)?.status)}}</span></div><p v-if="loading" role="status" class="muted">正在读取历史状态…</p><button v-if="nextBefore!==null" class="quiet-button" :disabled="older" @click="loadOlder">加载更早记录</button>
          <article v-for="turn in conversation?.turns || []" :key="turn.id" class="coding-turn" :data-turn="turn.id"><div class="coding-user-message">{{turn.text}}<div class="assistant-attached"><a v-for="file in turn.attachments || []" :key="file.id" :href="contentUrl(file)" :download="file.name">{{file.name}}</a></div></div><div class="coding-agent-label"><span class="agent-mini">✦</span> 通用助手 · {{mode==='work'?'Work':'Chat'}}<span class="turn-model">{{turn.modelSnapshot?.name || turn.modelProfileId}}</span><small>{{status(turn.status)}}</small></div><details v-if="turn.brief" class="assistant-brief-record"><summary>本轮已确认的工作范围</summary><p>目标：{{turn.brief.goal}}</p><p>成果：{{turn.brief.deliverable}}</p><p v-if="turn.brief.constraints">约束：{{turn.brief.constraints}}</p></details><details v-if="turn.events?.length" class="coding-tool-event" :open="mode==='work' && turn.status==='RUNNING'"><summary>{{mode==='work'?'执行进度':'执行记录'}} · {{turn.events.length}}</summary><ol class="assistant-events"><li v-for="(event,index) in turn.events" :key="index">{{event.label}}</li></ol></details><ReplyMarkdown v-if="turn.answer" :text="turn.answer"/><p v-else-if="turn.status==='RUNNING'" class="coding-thinking"><span></span>正在处理任务…</p><p v-if="turn.error" class="coding-error">{{turn.error}}</p><div class="assistant-artifacts"><a v-for="resource in turn.artifacts || []" :key="resource.id" :href="contentUrl(resource)" :download="resource.name"><StudioIcon name="document" :size="20"/>{{resource.name}}<StudioIcon name="download" :size="16"/></a><button v-if="turn.answer" class="quiet-button" @click="downloadText(turn)">下载回复 Markdown</button></div></article><div v-if="pending" class="assistant-pending"><p>{{pending.text}}</p><p>提交结果待确认，请先刷新状态。</p><button class="quiet-button" :disabled="busy" @click="refresh">刷新状态</button><button class="quiet-button" :disabled="busy" @click="send(true)">重试同一条消息</button></div>
        </div>
      </section>
      <aside v-if="mode==='work' && resultPanel && selected" class="coding-file-panel assistant-result-panel" aria-label="成果面板"><div class="panel-heading"><h2>成果与材料</h2><button class="icon-button" aria-label="关闭成果面板" @click="resultPanel=false">×</button></div><p v-if="!taskResources.length" class="muted">任务完成后，可在这里查看实际生成的文件。正文成果也可直接审阅与下载。</p><article v-for="resource in taskResources" :key="resource.id" class="assistant-resource-card"><img v-if="resource.mime?.startsWith('image/')" :src="contentUrl(resource,true)" :alt="resource.name" @click="preview=resource"/><strong>{{resource.name}}</strong><a :href="contentUrl(resource)" :download="resource.name">下载文件</a></article><details v-if="latestAnswer" class="assistant-output-preview" open><summary>最近成果预览</summary><ReplyMarkdown :text="latestAnswer"/></details></aside>
    </div>
    <footer class="coding-composer-zone assistant-composer-zone"><p v-if="connection" class="coding-service-note" role="status">{{connection}}</p><p v-if="error" class="coding-error" role="alert">{{error}} <button @click="selected?refresh():load()">刷新</button></p>
      <form class="coding-composer" @submit.prevent="send()">
        <details v-if="mode==='work'" class="assistant-work-brief" :open="showBrief"><summary @click.prevent="showBrief=!showBrief">本轮工作范围 <small>确认目标与成果后执行</small></summary><div class="assistant-brief-grid"><label>工作目标<input v-model="goal" data-testid="assistant-goal" maxlength="1000" placeholder="要解决什么问题" :disabled="busy||running||!!pending"/></label><label>交付成果<input v-model="deliverable" data-testid="assistant-deliverable" maxlength="1000" placeholder="例如：研究报告、Word 文稿、数据表" :disabled="busy||running||!!pending"/></label><label class="full">限制与要求<input v-model="constraints" data-testid="assistant-constraints" maxlength="2000" placeholder="范围、格式、证据要求或待澄清事项（选填）" :disabled="busy||running||!!pending"/></label></div><label class="assistant-confirm"><input v-model="confirmed" data-testid="assistant-confirmed" type="checkbox" :disabled="busy||running||!!pending"/>我已审阅本轮工作范围，同意按现有权限执行</label></details>
        <div class="assistant-attachment-chips"><span v-for="(file,index) in attachments" :key="file.id">{{file.name}}<button type="button" :aria-label="'移除 '+file.name" :disabled="busy||running||!!pending" @click="attachments.splice(index,1)">×</button></span></div>
        <textarea v-model="draft" data-testid="assistant-prompt" aria-label="助手消息" rows="2" maxlength="12000" :disabled="busy||!!pending" :placeholder="mode==='work'?'补充任务要求或材料；Ctrl / ⌘ + Enter 提交':'想问什么，或从一个想法开始…'" @keydown="composeKeys"></textarea>
        <div class="coding-composer-toolbar"><div class="composer-controls"><button type="button" class="composer-chip" :disabled="busy||running||uploading||!!pending" @click="fileInput.click()" aria-label="上传材料"><StudioIcon name="plus" :size="17"/>{{uploading?'上传中':'材料'}}</button><select v-if="mode==='work'" v-model="skill" aria-label="本轮技能" :disabled="busy||running||!!pending" class="assistant-skill"><option value="auto">按任务需要</option><option v-for="s in textSkills" :key="s.id" :value="s.id">{{s.name}}</option></select><PartnerModelPicker v-model:reasoning-effort="reasoningEffort" :key="space" :partner-id="space" v-model:model-profile-id="model" :disabled="busy||running||!!pending" @ready="modelReady=$event"/></div><button v-if="running" type="button" class="coding-stop" data-testid="assistant-cancel" aria-label="停止任务" :disabled="busy" @click="stop"><span></span></button><button v-else class="coding-send" type="submit" data-testid="assistant-send" aria-label="发送消息" :disabled="busy||loading||uploading||!!pending||!modelReady||!draft.trim()"><StudioIcon name="arrow" :size="21"/></button></div><input ref="fileInput" type="file" hidden multiple accept=".csv,.xlsx,.txt,.md,.docx,.pdf,.png,.jpg,.jpeg" @change="upload"/>
      </form><div class="assistant-footnote"><span>{{mode==='work'?'切换空间后任务继续，执行状态与成果按账号保存。':'优先直接回答；按现有材料与权限处理请求。'}} 图片仅保存预览。</span><button v-if="draft||latestAnswer" @click="prepareHandoff">转交到 Coding…</button></div>
    </footer>
    <div v-if="handoff" class="assistant-dialog-backdrop" @click.self="closeHandoff"><section ref="handoffPanel" class="assistant-dialog" role="dialog" aria-modal="true" aria-label="转交到 Coding Agent"><h2>审阅转交内容</h2><p>只把下方文字添加到 Coding 新任务草稿。进入后选择项目并主动发送；不会自动携带聊天记录、文件或授权。</p><textarea v-model="handoffText" aria-label="转交的需求材料与目标" rows="10" maxlength="12000"/><footer><button @click="closeHandoff">取消</button><button class="coding-primary" :disabled="!handoffText.trim()" @click="transfer">确认转交草稿</button></footer></section></div>
    <div v-if="preview" class="assistant-dialog-backdrop" @click.self="preview=null"><section ref="previewPanel" class="assistant-dialog" role="dialog" aria-modal="true" aria-label="图片预览"><button @click="preview=null">关闭预览</button><img :src="contentUrl(preview,true)" :alt="preview.name" style="max-width:100%"/></section></div>
  </main>
</template>
