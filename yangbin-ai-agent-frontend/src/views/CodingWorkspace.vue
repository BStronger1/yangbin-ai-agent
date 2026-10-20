<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import StudioIcon from '../components/StudioIcon.vue'
import ReplyMarkdown from '../components/ReplyMarkdown.vue'
import ModelManager from '../components/ModelManager.vue'
import { modelApi } from '../api/models'
import { codingApi } from '../api/coding'
import { chatWithManus } from '../api'
import { auth, checkSession } from '../auth'
import { api, errorMessage } from '../workspace-api'
import '../coding-workspace.css'

const route = useRoute(), router = useRouter()
const mode = computed(() => route.path === '/super-agent' || route.query.mode === 'chat' ? 'chat' : 'work')
const projects = ref([]), sessions = ref([]), session = ref(null), capabilities = ref(null)
const projectId = ref(''), draft = ref(''), error = ref(''), busy = ref(false), loading = ref(true)
const policy = ref('on-risk'), policyOpen = ref(false), historyOpen = ref(false), projectOpen = ref(false)
const projectName = ref(''), projectBusy = ref(false), filePanel = ref(false), files = ref([]), changes = ref([]), openedFile = ref(null)
const chatId = ref(''), chatHistory = ref([])
const conversation = ref(null), promptInput = ref(null), chatMessages = ref([]), chatEvents = ref([]), chatRunning = ref(false)
const pending = ref(null), fileEditing = ref(false), filePath = ref(''), fileContent = ref(''), fileSaving = ref(false)
const modelCatalog = ref({ profiles: [], providers: [], defaultModelId: 'system' }), selectedModels = ref({ work: 'system', chat: 'system' })
const modelPickerOpen = ref(false), modelManagerOpen = ref(false), modelsLoading = ref(true), modelsError = ref(''), capabilitiesLoading = ref(false)
const capabilitiesModelId = ref(''), chatModelSnapshot = ref(null)
let pollTimer = null, source = null, generation = 0, disposed = false, capabilityGeneration = 0, modelGeneration = 0
const policies = [
  { id: 'ask', label: '始终询问', detail: '每次调用工具前都需要确认' },
  { id: 'on-risk', label: '按需确认', detail: '修改文件和运行命令时确认' },
  { id: 'allow', label: '全部允许', detail: '允许在当前项目权限范围内使用工具' }
]
const policyLabel = computed(() => policies.find(item => item.id === policy.value)?.label || '按需确认')
const selectedProject = computed(() => projects.value.find(item => item.id === (session.value?.projectId || projectId.value)))
const workRunning = computed(() => ['RUNNING', 'QUEUED', 'WAITING_APPROVAL', 'AWAITING_APPROVAL', 'CANCELLING'].includes(String(session.value?.status || '').toUpperCase()))
const running = computed(() => mode.value === 'chat' ? chatRunning.value : workRunning.value)
const effectiveModelId = computed(() => {
  if (mode.value === 'chat') return chatRunning.value ? chatModelSnapshot.value?.profileId || 'system' : selectedModels.value.chat
  return pending.value?.modelProfileId || (workRunning.value ? session.value?.turns?.at(-1)?.modelProfileId || 'system' : selectedModels.value.work)
})
const selectedProfile = computed(() => modelCatalog.value.profiles.find(profile => profile.id === effectiveModelId.value))
const modelLabel = computed(() => effectiveModelId.value === 'system' ? '系统默认' : selectedProfile.value?.name || session.value?.turns?.at(-1)?.modelSnapshot?.name || '模型不可用')
const workAvailable = computed(() => !modelsLoading.value && !capabilitiesLoading.value && capabilitiesModelId.value === effectiveModelId.value && capabilities.value?.available)
const hasContent = computed(() => mode.value === 'chat' ? chatMessages.value.length > 0 : Boolean(session.value))
const pendingApprovals = computed(() => {
  if (!workRunning.value) return []
  const currentTurn = session.value?.turns?.at(-1)?.id
  const events = (session.value?.events || []).filter(event => event.turnId === currentTurn)
  const resolved = new Set(events.filter(item => item.type === 'approval_resolved').map(item => item.approvalId))
  return events.filter(item => item.type === 'approval_required' && !resolved.has(item.approvalId))
})
const turns = computed(() => (session.value?.turns || []).map(turn => {
  const events = (session.value.events || []).filter(event => event.turnId === turn.id)
  return { ...turn, text: events.filter(event => event.type === 'text_delta').map(event => event.delta ?? event.text ?? '').join(''), events: events.filter(event => ['tool_start', 'tool_end', 'error'].includes(event.type)) }
}))
const suggestions = computed(() => mode.value === 'chat' ? [
  { icon: 'bulb', title: '一起拆解一个问题', prompt: '请和我一起拆解这个问题，先帮我明确目标和需要的信息：' },
  { icon: 'notes', title: '整理思路与行动计划', prompt: '请把下面的想法整理成清晰的行动计划：' }
] : [
  { icon: 'folder', title: '了解这个项目', prompt: '请先查看当前项目的文件，说明项目结构、运行方式和下一步可以做的工作。' },
  { icon: 'layout', title: '从想法开始构建', prompt: '请在当前项目中构建一个简洁的待办清单网页，支持添加、完成、删除和本地保存，并检查主要交互。' },
  { icon: 'check', title: '检查代码并修复问题', prompt: '请检查当前项目中的代码，定位一个有证据的问题，给出修复并执行可用的验证。' }
])
const modelPreferenceKey = (owner, currentMode) => `coding-model:${owner}:${currentMode}`
function rememberModel(currentMode) { try { sessionStorage.setItem(modelPreferenceKey(auth.user?.id || auth.user?.username, currentMode), selectedModels.value[currentMode]) } catch { /* A disabled browser store must not prevent a request. */ } }
function chooseModel(profileId) { if (running.value || busy.value || pending.value) return; selectedModels.value[mode.value] = profileId; rememberModel(mode.value); modelPickerOpen.value = false }
function applyModelCatalog(value, initialize = false) {
  modelCatalog.value = value
  for (const currentMode of ['work', 'chat']) {
    let choice = selectedModels.value[currentMode]
    if (initialize) { try { choice = sessionStorage.getItem(modelPreferenceKey(auth.user?.id || auth.user?.username, currentMode)) || value.defaultModelId || 'system' } catch { choice = value.defaultModelId || 'system' } }
    if (choice !== 'system' && !value.profiles.some(profile => profile.id === choice)) choice = value.defaultModelId || 'system'
    selectedModels.value[currentMode] = choice; rememberModel(currentMode)
  }
}
async function reloadModels(selectId) {
  const owner = auth.user?.id || auth.user?.username, token = ++modelGeneration
  modelsLoading.value = true; modelsError.value = ''
  try { const value = await modelApi.list(); if (disposed || token !== modelGeneration || owner !== (auth.user?.id || auth.user?.username)) return; applyModelCatalog(value); if (selectId && !running.value && !pending.value) chooseModel(selectId) }
  catch { if (token === modelGeneration) modelsError.value = '模型配置未能加载，请重试。' }
  finally { if (token === modelGeneration) { modelsLoading.value = false; refreshCapabilities() } }
}
async function refreshCapabilities() {
  if (mode.value !== 'work' || disposed || modelsLoading.value) return
  const choice = effectiveModelId.value, owner = auth.user?.id || auth.user?.username, token = ++capabilityGeneration
  capabilitiesLoading.value = true
  try { const value = await codingApi.capabilities(choice); if (disposed || token !== capabilityGeneration || owner !== (auth.user?.id || auth.user?.username)) return; capabilities.value = value; capabilitiesModelId.value = choice }
  catch { if (token === capabilityGeneration) { capabilities.value = { available: false, reason: '当前模型的 Coding Agent 状态不可用，请检查配置后重试。' }; capabilitiesModelId.value = choice } }
  finally { if (token === capabilityGeneration) capabilitiesLoading.value = false }
}
function historyModel(turn) { const value = turn.modelSnapshot; return value?.model ? `${value.name || value.provider || '模型'} · ${value.model}` : turn.modelProfileId ? (turn.modelProfileId === 'system' ? '系统默认' : '个人模型') : '历史记录未保存模型信息' }
function openModelManager() { if (running.value || busy.value || pending.value || modelsLoading.value) return; modelPickerOpen.value = false; modelManagerOpen.value = true }
function statusLabel(value) {
  return ({ IDLE: '准备就绪', RUNNING: '执行中', QUEUED: '排队中', WAITING_APPROVAL: '等待确认', AWAITING_APPROVAL: '等待确认', SUCCEEDED: '已完成', COMPLETED: '已完成', FAILED: '执行失败', CANCELLED: '已停止', INTERRUPTED: '已中断', CANCELLING: '正在停止' })[String(value || '').toUpperCase()] || value || '准备就绪'
}
const pretty = value => typeof value === 'string' ? value : JSON.stringify(value, null, 2)
const date = value => value ? new Date(value).toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' }) : ''
const id = () => globalThis.crypto?.randomUUID?.() || 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, char => { const r = Math.random() * 16 | 0; return (char === 'x' ? r : (r & 3 | 8)).toString(16) })
const pendingKey = sessionId => `coding-pending:${auth.user?.id || auth.user?.username}:${sessionId}`
function rememberPending() { if (session.value) { if (pending.value) sessionStorage.setItem(pendingKey(session.value.id), JSON.stringify(pending.value)); else sessionStorage.removeItem(pendingKey(session.value.id)) } }
function clearPendingIfAccepted() {
  if (pending.value && session.value?.turns?.some(turn => turn.id === pending.value.requestId || turn.requestId === pending.value.requestId)) { pending.value = null; rememberPending(); draft.value = '' }
}
async function scrollBottom(force = false) { const area = conversation.value; const near = !area || area.scrollHeight - area.scrollTop - area.clientHeight < 130; await nextTick(); if (conversation.value && (force || near)) conversation.value.scrollTop = conversation.value.scrollHeight }
function applySession(value) { session.value = value; projectId.value = value.projectId; policy.value = pending.value?.approvalMode || value.approvalMode || policy.value; clearPendingIfAccepted(); scrollBottom() }
function stopPoll() { clearTimeout(pollTimer); pollTimer = null }
function schedulePoll() { stopPoll(); if (!disposed && session.value && workRunning.value) pollTimer = setTimeout(refreshSession, 1200) }
async function refreshSession() {
  const current = session.value?.id, token = generation
  if (!current) return
  try { const value = await codingApi.session(current); if (disposed || token !== generation || session.value?.id !== current) return; applySession(value); error.value = ''; if (!workRunning.value) { await loadHistory(); if (filePanel.value) await loadFiles() } }
  catch (e) { if (token === generation) error.value = '状态更新暂时中断，正在重连；不会自动重复提交任务。' }
  finally { if (token === generation) schedulePoll() }
}
async function loadHistory() { const owner = auth.user?.id || auth.user?.username; const value = await codingApi.sessions(); if (!disposed && owner === (auth.user?.id || auth.user?.username)) sessions.value = value }
async function loadSession(sessionId) {
  const token = ++generation; stopPoll(); busy.value = false; session.value = null; pending.value = null; openedFile.value = null; changes.value = []; files.value = []; filePanel.value = false; fileEditing.value = false; fileContent.value = ''; error.value = ''; draft.value = ''
  if (!sessionId) return
  loading.value = true
  try {
    const value = await codingApi.session(sessionId)
    if (disposed || token !== generation) return
    try { pending.value = JSON.parse(sessionStorage.getItem(pendingKey(sessionId)) || 'null') } catch { pending.value = null }
    applySession(value); if (pending.value) { pending.value = { revision: value.revision, approvalMode: value.approvalMode, modelProfileId: 'system', ...pending.value }; draft.value = pending.value.prompt; rememberPending() }
    scrollBottom(true); schedulePoll()
  } catch (e) { if (token === generation) error.value = errorMessage(e) }
  finally { if (token === generation) loading.value = false }
}
function closeChat() { source?.close(); source = null; chatRunning.value = false }
function newTask(nextMode = mode.value) { if (busy.value || running.value) return; closeChat(); chatId.value = ''; chatHistory.value = []; chatMessages.value = []; chatEvents.value = []; draft.value = ''; error.value = ''; historyOpen.value = false; router.push({ path: '/work', query: nextMode === 'chat' ? { mode: 'chat' } : {} }) }
function selectMode(nextMode) { if (nextMode !== mode.value) newTask(nextMode) }
function openSession(item) { if (busy.value) return; historyOpen.value = false; router.push({ path: '/work', query: { session: item.id } }) }
async function createProject() {
  if (!projectName.value.trim() || projectBusy.value) return
  projectBusy.value = true; error.value = ''
  try { const value = await codingApi.createProject(projectName.value.trim()); projects.value.push(value); projectId.value = value.id; projectName.value = ''; projectOpen.value = false }
  catch (e) { error.value = errorMessage(e) } finally { projectBusy.value = false }
}
async function send() {
  if (!draft.value.trim() || busy.value || running.value) return
  if (mode.value === 'chat') { sendChat(); return }
  if (!workAvailable.value || !projectId.value) return
  busy.value = true; error.value = ''; const token = generation
  try {
    if (!session.value) {
      const created = await codingApi.createSession({ projectId: projectId.value, title: draft.value.trim().slice(0, 60), approvalMode: policy.value })
      if (token !== generation || disposed) return
      applySession(created)
      await router.replace({ path: '/work', query: { session: created.id } })
    }
    const currentId = session.value.id
    if (!pending.value) pending.value = { requestId: id(), prompt: draft.value.trim(), revision: session.value.revision, approvalMode: policy.value, modelProfileId: effectiveModelId.value }
    rememberPending()
    const value = await codingApi.turn(currentId, { ...pending.value })
    if (disposed || session.value?.id !== currentId) return
    applySession(value); pending.value = null; rememberPending(); draft.value = ''; await loadHistory(); scrollBottom(true); schedulePoll()
  } catch (e) {
    if (e.response?.status >= 400 && e.response?.status < 500) { pending.value = null; rememberPending(); error.value = errorMessage(e) }
    else error.value = pending.value ? '提交结果尚未确认。请刷新状态；若未创建本轮，可重试原消息（沿用同一请求编号）。' : errorMessage(e)
    if (session.value) schedulePoll()
  }
  finally { busy.value = false }
}
async function showHistory() {
  historyOpen.value = !historyOpen.value
  if (historyOpen.value && mode.value === 'chat') {
    const owner=auth.user?.id || auth.user?.username
    try { const {data}=await api.get('/ai/manus/conversations'); if(owner===(auth.user?.id || auth.user?.username))chatHistory.value=data } catch(e) {error.value=errorMessage(e)}
  }
}
async function openChat(cid) {
  if (chatRunning.value || busy.value) return
  const owner=auth.user?.id || auth.user?.username, token=++generation
  try { const {data}=await api.get('/ai/manus/conversations/'+cid); if(disposed||token!==generation||owner!==(auth.user?.id || auth.user?.username))return
    chatId.value=cid; chatMessages.value=data.turns.flatMap(t=>[{id:t.id+'u',role:'user',text:t.prompt},{id:t.id+'a',role:'assistant',text:t.answer || '任务状态：'+statusLabel(t.status)}]);historyOpen.value=false
    error.value=data.turns.at(-1)?.status==='RUNNING'?'任务仍在服务端执行，请稍后重新打开历史查看结果。':''
    router.replace({path:'/work',query:{mode:'chat',chat:cid}});scrollBottom(true)
  }catch(e){error.value=errorMessage(e)}
}
async function deleteChat(cid) {
  if(chatRunning.value||!confirm('删除此对话历史？已创建的文件不会删除。'))return
  try{await api.delete('/ai/manus/conversations/'+cid);chatHistory.value=chatHistory.value.filter(c=>c.id!==cid);if(chatId.value===cid)newTask('chat')}catch(e){error.value=errorMessage(e)}
}
function sendChat() {
  const prompt = draft.value.trim(), profileId = effectiveModelId.value; chatModelSnapshot.value = { profileId, name: modelLabel.value, model: selectedProfile.value?.model || '' }; draft.value = ''; error.value = ''; closeChat(); chatEvents.value = []
  chatMessages.value.push({ id: id(), role: 'user', text: prompt }, { id: id(), role: 'assistant', text: '', modelSnapshot: { ...chatModelSnapshot.value } })
  const response = chatMessages.value[chatMessages.value.length - 1]
  if (!chatId.value) chatId.value = id();
  router.replace({path:'/work',query:{mode:'chat',chat:chatId.value}});
  chatRunning.value = true; source = chatWithManus(prompt, profileId, chatId.value, id()); const connection = source
  connection.onmessage = event => { if (source !== connection) return; if (event.data === '[DONE]') closeChat(); else { response.text += (response.text ? '\n\n' : '') + event.data; scrollBottom() } }
  connection.addEventListener('trace', event => { if (source !== connection) return; try { const value = JSON.parse(event.data); const index = chatEvents.value.findIndex(item => item.id === value.id); if (index < 0) chatEvents.value.push(value); else chatEvents.value[index] = value } catch { /* Non-JSON trace cannot be rendered. */ } })
  connection.addEventListener('failure', event => { if (source === connection) { error.value = event.data || '对话未完成'; closeChat() } })
  connection.onerror = () => { if (source === connection) { error.value = '对话连接已中断，已接收内容保留。'; closeChat(); checkSession() } }
  scrollBottom(true)
}
async function decide(approvalId, approved) {
  if (busy.value) return
  busy.value = true; const currentId = session.value.id
  try { const value = await codingApi.approve(currentId, approvalId, approved); if (session.value?.id === currentId) { applySession(value); error.value = ''; schedulePoll() } }
  catch (e) { error.value = errorMessage(e) } finally { busy.value = false }
}
async function cancel() {
  if (mode.value === 'chat') { const cid=chatId.value; closeChat(); try { if(cid) await api.post('/ai/manus/conversations/'+cid+'/cancel'); error.value = '已请求停止后续工具操作；已发出的供应商请求可能仍在运行。' } catch(e) { error.value=errorMessage(e) } return }
  if (!session.value || busy.value) return
  busy.value = true; const currentId = session.value.id
  try { const value = await codingApi.cancel(currentId); if (session.value?.id === currentId) { applySession(value); error.value = ''; schedulePoll() } }
  catch (e) { error.value = errorMessage(e) } finally { busy.value = false }
}
async function loadFiles() {
  const currentId = session.value?.id, project = session.value?.projectId || projectId.value
  if (!project) return
  try {
    const results = await Promise.all([codingApi.files(project), currentId ? codingApi.diff(currentId) : Promise.resolve([])])
    if ((session.value?.projectId || projectId.value) !== project || session.value?.id !== currentId) return
    files.value = results[0]; changes.value = results[1]
  } catch (e) { error.value = errorMessage(e) }
}
async function showFiles() { filePanel.value = !filePanel.value; if (filePanel.value) await loadFiles() }
function editFile(file = null) { filePath.value = file?.path || ''; fileContent.value = file?.content || ''; fileEditing.value = true }
async function saveFile() {
  if (!filePath.value.trim() || fileSaving.value || workRunning.value) return
  const project = session.value?.projectId || projectId.value; fileSaving.value = true; error.value = ''
  try { const value = await codingApi.putFile(project, filePath.value.trim(), fileContent.value); if ((session.value?.projectId || projectId.value) === project) { openedFile.value = value; fileEditing.value = false; await loadFiles() } }
  catch (e) { error.value = errorMessage(e) } finally { fileSaving.value = false }
}
async function openFile(path) { const project = session.value?.projectId || projectId.value; try { const value = await codingApi.file(project, path); if ((session.value?.projectId || projectId.value) === project) openedFile.value = value } catch (e) { error.value = errorMessage(e) } }
watch(effectiveModelId, () => { modelPickerOpen.value = false; if (mode.value === 'work') refreshCapabilities() })
watch(() => route.query.session, value => { if (value !== session.value?.id) loadSession(typeof value === 'string' ? value : null) })
watch(() => mode.value, () => { modelPickerOpen.value = false; modelManagerOpen.value = false; generation++; closeChat(); chatMessages.value = []; chatEvents.value = []; chatId.value=''; chatHistory.value=[]; draft.value = ''; policyOpen.value = false; error.value = ''; filePanel.value = false; if (mode.value === 'chat') { stopPoll(); session.value = null; pending.value = null } else refreshCapabilities() })
watch(() => auth.user?.id || auth.user?.username, (_, previousOwner) => { for (const currentMode of ['work','chat']) { try { sessionStorage.removeItem(modelPreferenceKey(previousOwner, currentMode)) } catch {} } modelGeneration++; capabilityGeneration++; modelPickerOpen.value = false; modelManagerOpen.value = false; modelCatalog.value = { profiles: [], providers: [], defaultModelId: 'system' }; selectedModels.value = { work: 'system', chat: 'system' }; chatModelSnapshot.value = null; capabilitiesModelId.value = ''; generation++; closeChat(); stopPoll(); session.value = null; sessions.value = []; projects.value = []; draft.value = ''; pending.value = null; chatMessages.value = []; chatEvents.value = []; chatId.value=''; chatHistory.value=[]; projectId.value = ''; projectName.value = ''; openedFile.value = null; files.value = []; changes.value = []; filePanel.value = false; fileEditing.value = false; fileContent.value = ''; projectOpen.value = false; historyOpen.value = false; policyOpen.value = false; capabilities.value = null })
onMounted(async () => {
  const owner = auth.user?.id || auth.user?.username
  const results = await Promise.allSettled([codingApi.capabilities(), codingApi.projects(), codingApi.sessions(), modelApi.list()])
  if (disposed || owner !== (auth.user?.id || auth.user?.username)) return
  if (results[0].status === 'fulfilled') capabilities.value = results[0].value
  else capabilities.value = { available: false, reason: 'Coding Agent 服务暂不可用，请稍后刷新。' }
  if (results[1].status === 'fulfilled') { projects.value = [...new Map([...results[1].value, ...projects.value].map(project => [project.id, project])).values()]; if (!projectId.value) projectId.value = projects.value[0]?.id || '' }
  if (results[2].status === 'fulfilled') sessions.value = results[2].value
  if (results[3].status === 'fulfilled') applyModelCatalog(results[3].value, true)
  else modelsError.value = '模型配置未能加载，请重试。'
  modelsLoading.value = false; refreshCapabilities()
  loading.value = false
  if (typeof route.query.session === 'string') await loadSession(route.query.session)
  if(mode.value==='chat'&&typeof route.query.chat==='string')await openChat(route.query.chat)
})
onBeforeUnmount(() => { disposed = true; modelGeneration++; capabilityGeneration++; generation++; closeChat(); stopPoll() })
</script>

<template>
  <main class="coding-workspace" data-testid="coding-workspace" @keydown.esc="policyOpen = false; projectOpen = false; historyOpen = false; modelPickerOpen = false">
    <header class="coding-topbar">
      <div class="coding-location"><StudioIcon :name="mode === 'work' ? 'layout' : 'chat'" :size="19" /><strong>{{ mode === 'work' ? 'Coding Agent' : 'AI 超级智能体' }}</strong><span v-if="session" class="coding-project-label">/ {{ selectedProject?.name || '项目' }}</span></div>
      <div class="coding-top-actions"><RouterLink class="quiet-button" to="/governance">用量与运行状态</RouterLink><button class="quiet-button" @click="showHistory" data-testid="coding-history"><StudioIcon name="clock" :size="18" /><span>{{ mode === 'chat' ? '对话历史' : '历史任务' }}</span></button><button v-if="mode === 'work' && (session || projectId)" class="quiet-button" @click="showFiles" data-testid="coding-files"><StudioIcon name="folder" :size="18" /><span>项目文件</span></button><button v-if="hasContent" class="quiet-button" :disabled="busy" @click="newTask()"><StudioIcon name="plus" :size="18" /><span>新任务</span></button></div>
    </header>
    <div v-if="historyOpen" class="coding-history-panel" data-testid="coding-history-panel"><div class="panel-heading"><h2>{{ mode === 'chat' ? '对话历史' : '工作任务' }}</h2><button class="icon-button" aria-label="关闭历史任务" @click="historyOpen = false"><StudioIcon name="close" :size="18" /></button></div><p v-if="!(mode === 'chat' ? chatHistory : sessions).length" class="muted">开始一个任务后，可以在这里继续。</p><button v-for="item in (mode === 'chat' ? chatHistory : sessions)" :key="item.id" class="history-row" :disabled="running" @click="mode === 'chat' ? openChat(item.id) : openSession(item)"><strong>{{ item.title || '未命名任务' }}</strong><span>{{ statusLabel(item.status) }} · {{ date(item.updatedAt || item.createdAt) }}</span></button><button v-if="mode === 'chat' && chatId && !running" class="quiet-button" @click="deleteChat(chatId)">删除当前对话</button></div>
    <div class="coding-body" :class="{ 'with-files': filePanel }">
      <section ref="conversation" class="coding-conversation" aria-label="任务内容" data-testid="coding-scroll">
        <div v-if="!hasContent" class="coding-welcome">
          <div class="coding-orbit" aria-hidden="true"><span></span></div>
          <h1>{{ mode === 'work' ? '今天有什么工作要处理？' : '今天想聊些什么？' }}</h1>
          <div class="coding-mode-switch" role="group" aria-label="选择智能体模式"><button :class="{ active: mode === 'chat' }" :aria-pressed="mode === 'chat'" @click="selectMode('chat')" data-testid="mode-chat">对话</button><button :class="{ active: mode === 'work' }" :aria-pressed="mode === 'work'" @click="selectMode('work')" data-testid="mode-work">工作</button></div>
          <p class="coding-mode-description">{{ mode === 'work' ? '从一个想法到可运行的代码，一起完成。' : '和 AI 超级智能体交流、探索与解决问题。' }}</p>
          <div class="coding-suggestions"><p>为你推荐</p><button v-for="item in suggestions" :key="item.title" @click="draft = item.prompt; promptInput?.focus()"><StudioIcon :name="item.icon" :size="19" /><span>{{ item.title }}</span><StudioIcon name="arrow" :size="16" /></button></div>
          <div v-if="mode === 'work' && sessions.length" class="coding-recent"><div class="recent-heading"><span>最近的工作</span><button @click="historyOpen = true">查看全部</button></div><button v-for="item in sessions.slice(0, 3)" :key="item.id" @click="openSession(item)"><span>{{ item.title || '未命名任务' }}</span><small>{{ statusLabel(item.status) }}</small></button></div>
        </div>
        <div v-else class="coding-thread">
          <template v-if="mode === 'work'">
            <div class="thread-heading"><h1>{{ session.title || '工作任务' }}</h1><span class="status-chip" :class="{ running: workRunning }" role="status">{{ statusLabel(session.status) }}</span></div>
            <p v-if="!turns.length" class="muted">工作区已准备好，发送消息开始。</p>
            <article v-for="turn in turns" :key="turn.id" class="coding-turn" :data-turn="turn.id"><div class="coding-user-message">{{ turn.prompt }}</div><div class="coding-agent-label"><span class="agent-mini">✦</span> Coding Agent <span class="turn-model" :title="historyModel(turn)">{{ historyModel(turn) }}</span><small>{{ statusLabel(turn.status) }}</small></div><div v-if="turn.events.length" class="coding-tool-events"><details v-for="event in turn.events" :key="event.seq" class="coding-tool-event" :class="{ 'tool-error': event.type === 'error' || event.ok === false }"><summary><StudioIcon :name="event.type === 'error' || event.ok === false ? 'close' : event.type === 'tool_end' ? 'check' : 'notes'" :size="15" /><span>{{ event.type === 'error' ? event.message : `${event.type === 'tool_start' ? '调用' : event.ok === false ? '失败' : '完成'} ${event.toolName || '工具'}` }}</span></summary><pre>{{ pretty(event.arguments ?? event.output ?? event.error ?? event.result ?? event.message) }}</pre></details></div><ReplyMarkdown v-if="turn.text" :text="turn.text" /><p v-else-if="['RUNNING','QUEUED'].includes(String(turn.status).toUpperCase())" class="coding-thinking"><span></span>正在处理任务…</p></article>
            <section v-for="approval in pendingApprovals" :key="approval.approvalId" class="coding-approval" data-testid="coding-approval"><div><StudioIcon name="check" :size="20" /><h2>需要你的确认</h2></div><p>{{ approval.reason || '智能体请求执行以下操作。' }}</p><strong>{{ approval.toolName }}</strong><pre>{{ pretty(approval.arguments) }}</pre><footer><button class="quiet-button" :disabled="busy" @click="decide(approval.approvalId, false)" data-testid="approval-deny">拒绝</button><button class="coding-primary" :disabled="busy" @click="decide(approval.approvalId, true)" data-testid="approval-allow">允许这次操作</button></footer></section>
          </template>
          <template v-else><article v-for="message in chatMessages" :key="message.id" class="coding-turn"><div v-if="message.role === 'user'" class="coding-user-message">{{ message.text }}</div><template v-else><div class="coding-agent-label"><span class="agent-mini">✦</span> AI 超级智能体<span class="turn-model">{{ message.modelSnapshot?.model || message.modelSnapshot?.name }}</span></div><ReplyMarkdown v-if="message.text" :text="message.text" /><p v-else-if="chatRunning" class="coding-thinking"><span></span>正在思考…</p></template></article><details v-if="chatEvents.length" class="coding-tool-event"><summary>本轮工具执行记录 · {{ chatEvents.length }}</summary><pre>{{ pretty(chatEvents) }}</pre></details></template>
        </div>
      </section>
      <aside v-if="filePanel" class="coding-file-panel" data-testid="coding-file-panel"><div class="panel-heading"><h2>项目文件</h2><button class="icon-button" aria-label="关闭文件面板" @click="filePanel = false"><StudioIcon name="close" :size="18" /></button></div><p class="muted">{{ selectedProject?.name }} · 服务端工作区</p><div class="file-panel-actions"><button class="quiet-button" @click="loadFiles"><StudioIcon name="clock" :size="15" />刷新文件</button><button class="quiet-button" :disabled="workRunning" @click="editFile()" data-testid="new-file"><StudioIcon name="plus" :size="15" />新建文件</button></div><div v-if="fileEditing" class="coding-file-editor"><label>项目相对路径<input v-model="filePath" placeholder="例如 src/index.js" aria-label="文件路径" :disabled="fileSaving" /></label><label>文件内容<textarea v-model="fileContent" rows="12" aria-label="文件内容" :disabled="fileSaving"></textarea></label><footer><button class="quiet-button" :disabled="fileSaving" @click="fileEditing = false">取消</button><button class="coding-primary" :disabled="fileSaving || !filePath.trim() || workRunning" @click="saveFile" data-testid="save-file">保存文件</button></footer></div><p v-if="!files.length" class="muted file-empty">项目中暂无文件</p><button v-for="file in files" :key="file.path" class="coding-file-row" @click="openFile(file.path)"><StudioIcon name="document" :size="16" />{{ file.path }}</button><details v-if="openedFile" class="coding-file-preview" open><summary>{{ openedFile.path }}</summary><button class="quiet-button" :disabled="workRunning" @click="editFile(openedFile)">编辑文件</button><pre>{{ openedFile.content }}</pre></details><h3>最近一轮文件变更 <span>{{ changes.length }}</span></h3><p v-if="!changes.length" class="muted">暂无已记录的文件变更</p><details v-for="change in changes" :key="change.path" class="coding-diff"><summary>{{ change.kind }} · {{ change.path }}</summary><label>修改前</label><pre>{{ change.before ?? '（文件不存在）' }}</pre><label>修改后</label><pre>{{ change.after ?? '（文件已删除）' }}</pre></details></aside>
    </div>
    <footer class="coding-composer-zone">
      <p v-if="error" class="coding-error" role="alert">{{ error }} <button v-if="mode === 'work' && session" @click="refreshSession">刷新状态</button></p>
      <p v-if="modelsError" class="coding-service-note" role="status">{{ modelsError }} <button class="model-note-action" @click="reloadModels()">重试</button></p><p v-if="mode === 'work' && !modelsLoading && !capabilitiesLoading && capabilities && !capabilities.available" class="coding-service-note" role="status">{{ capabilities.reason || 'Coding Agent 暂未就绪' }} <button class="model-note-action" :disabled="running || busy || Boolean(pending)" @click="openModelManager">配置模型</button></p>
      <form class="coding-composer" @submit.prevent="send" data-testid="coding-composer">
        <textarea ref="promptInput" v-model="draft" rows="2" maxlength="16000" :disabled="busy || Boolean(pending)" :placeholder="mode === 'work' ? '描述你要完成的工作，或继续当前任务…' : '给 AI 超级智能体发消息…'" aria-label="消息内容" data-testid="coding-prompt" @keydown.enter.exact.prevent="!$event.isComposing && send()"></textarea>
        <div class="coding-composer-toolbar"><div class="composer-controls">
          <template v-if="mode === 'work'"><span class="composer-chip environment-chip" title="文件工具限定服务端项目目录；命令仅在隔离环境验证通过后启用"><StudioIcon name="layout" :size="17" />服务端工作区</span><div class="project-control"><button type="button" class="composer-chip project-chip" :disabled="Boolean(session) || busy" :aria-expanded="projectOpen" @click="projectOpen = !projectOpen; policyOpen = false" data-testid="project-picker"><StudioIcon name="folder" :size="17" /><span>{{ selectedProject?.name || '选择项目' }}</span><span>⌄</span></button><div v-if="projectOpen" class="composer-popover project-popover"><h2>选择项目</h2><button v-for="project in projects" :key="project.id" type="button" @click="projectId = project.id; projectOpen = false"><StudioIcon name="folder" :size="17" />{{ project.name }}<StudioIcon v-if="projectId === project.id" name="check" :size="16" /></button><p v-if="!projects.length" class="muted">创建项目后即可开始编码</p><div class="create-project"><input v-model="projectName" maxlength="80" placeholder="新项目名称" aria-label="新项目名称" @keydown.enter.prevent="createProject" /><button type="button" :disabled="!projectName.trim() || projectBusy" @click="createProject" data-testid="create-project">创建</button></div><small>新建空白项目，文件保存在服务端。</small></div></div><div class="policy-control"><button type="button" class="composer-chip" :disabled="running || busy || Boolean(pending)" :aria-expanded="policyOpen" @click="policyOpen = !policyOpen; projectOpen = false" data-testid="approval-policy"><StudioIcon name="check" :size="17" />{{ policyLabel }}<span>⌄</span></button><div v-if="policyOpen" class="composer-popover policy-popover"><button v-for="item in policies" :key="item.id" type="button" :class="{ selected: item.id === policy }" @click="policy = item.id; policyOpen = false" :data-testid="`policy-${item.id}`"><StudioIcon :name="item.id === 'ask' ? 'person' : item.id === 'allow' ? 'spark' : 'check'" :size="19" /><span><strong>{{ item.label }}</strong><small>{{ item.detail }}</small></span><StudioIcon v-if="item.id === policy" name="check" :size="18" /></button></div></div></template>
          <span v-else class="composer-chip"><StudioIcon name="spark" :size="17" />AI 超级智能体</span>
          <div class="model-picker-control"><button type="button" class="composer-chip model-picker-trigger" :disabled="running || busy || Boolean(pending) || modelsLoading" :aria-expanded="modelPickerOpen" @click="modelPickerOpen = !modelPickerOpen; projectOpen = false; policyOpen = false" data-testid="model-picker"><StudioIcon name="spark" :size="16" /><span>{{ modelsLoading ? '加载模型…' : modelLabel }}</span><span>⌄</span></button><div v-if="modelPickerOpen" class="composer-popover model-picker-popover"><p>为{{ mode === 'work' ? '工作' : '对话' }}选择模型</p><button type="button" :class="{ selected: effectiveModelId === 'system' }" @click="chooseModel('system')" data-testid="model-option-system"><span><strong>系统默认</strong><small>使用当前部署的模型配置</small></span><StudioIcon v-if="effectiveModelId === 'system'" name="check" :size="16" /></button><button v-for="profile in modelCatalog.profiles" :key="profile.id" type="button" :class="{ selected: effectiveModelId === profile.id }" @click="chooseModel(profile.id)" :data-testid="`model-option-${profile.id}`"><span><strong>{{ profile.name }}</strong><small>{{ profile.model }}</small></span><StudioIcon v-if="effectiveModelId === profile.id" name="check" :size="16" /></button><button type="button" class="model-manage-link" @click="openModelManager" data-testid="model-manage"><StudioIcon name="plus" :size="16" />管理模型与 API Key</button></div></div><RouterLink to="/skills?tab=skills" class="composer-link"><StudioIcon name="layers" :size="17" />技能</RouterLink><RouterLink to="/mcp" class="composer-link"><StudioIcon name="plug" :size="17" />插件</RouterLink>
        </div><button v-if="running" type="button" class="coding-stop" :disabled="busy" aria-label="停止任务" @click="cancel" data-testid="coding-cancel"><span></span></button><button v-else type="submit" class="coding-send" :disabled="busy || !draft.trim() || (mode === 'work' && (!workAvailable || !projectId))" :aria-label="pending ? '重试原消息' : '发送消息'" data-testid="coding-send"><StudioIcon name="arrow" :size="21" /></button></div>
      </form>
      <p class="composer-footnote">{{ mode === 'work' ? (capabilities?.shellEnabled ? '服务端项目文件 · 隔离命令执行已启用（无网络、只读项目快照）' : '当前支持项目文件读取与编辑 · 命令执行未启用或隔离环境不可用') : '当前对话由 AI 超级智能体执行 · 历史按账号保存，可从历史列表恢复' }}</p>
    </footer>
    <ModelManager v-if="modelManagerOpen" :catalog="modelCatalog" @close="modelManagerOpen = false" @changed="reloadModels" />
  </main>
</template>
