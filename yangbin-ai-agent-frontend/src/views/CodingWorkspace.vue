<script setup>
import { LocalRunner } from '../local-runner'
import { LocalDirectory, localSupported, saveLocalHandle, restoreLocalHandle } from '../local-directory'

import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter, onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router'
import { useOverlay, isComposing } from '../composables/useOverlay'
import { useUnsavedChanges } from '../composables/useUnsavedChanges'
import StudioIcon from '../components/StudioIcon.vue'
import ReplyMarkdown from '../components/ReplyMarkdown.vue'
import ReasoningEffort from '../components/ReasoningEffort.vue'
import CodingTeam from '../components/CodingTeam.vue'
import CodingMemory from '../components/CodingMemory.vue'
import CodingEditorLink from '../components/CodingEditorLink.vue'
import { modelApi } from '../api/models'
import { codingApi } from '../api/coding'
import { chatWithManus } from '../api'
import { auth, checkSession } from '../auth'
import { api, errorMessage } from '../workspace-api'
import '../coding-workspace.css'
import { readWorkspace, writeWorkspace } from '../workspace-state'

const route = useRoute(), router = useRouter()
const mode = computed(() => route.path === '/super-agent' ? 'chat' : 'work')
const codingDraftKey = () => 'draft:' + (session.value?.id || 'new')
let restoringWorkspace = false, workspaceAccount = auth.user?.id || auth.user?.username, legacyPoll = null
function saveWorkspace() { if (restoringWorkspace || loading.value || mode.value !== 'work') return; writeWorkspace('coding', codingDraftKey(), draft.value, workspaceAccount); writeWorkspace('coding', 'layout', { files: filePanel.value }, workspaceAccount); writeWorkspace('coding', 'project', projectId.value, workspaceAccount) }
function restoreWorkspace() { restoringWorkspace = true; draft.value = pending.value?.prompt || readWorkspace('coding', codingDraftKey(), ''); filePanel.value = readWorkspace('coding', 'layout', { files: false }).files === true; restoringWorkspace = false }
const projects = ref([]), sessions = ref([]), session = ref(null), capabilities = ref(null)
const memoryOpen = ref(false)
const projectId = ref(''), draft = ref(''), error = ref(''), busy = ref(false), loading = ref(true)
const executionMode = ref('auto')
const executionModes = [{id:'auto',label:'自动选择'},{id:'direct',label:'直接回答'},{id:'react',label:'工具驱动'},{id:'plan',label:'规划与执行'},{id:'check',label:'检查与修正'},{id:'team',label:'团队执行（需服务端执行器）'}]
const policy = ref('on-risk'), policyOpen = ref(false), historyOpen = ref(false), projectOpen = ref(false)
const projectName = ref(''), projectBusy = ref(false), filePanel = ref(false), files = ref([]), changes = ref([]), openedFile = ref(null)
const fileQuery = ref(''), changedOnly = ref(false), filesLoading = ref(false), filesError = ref(''), previewLoading = ref(false), previewError = ref(''), fileNotice = ref('')
let filesSequence = 0, previewSequence = 0
const changedPaths = computed(() => new Set(changes.value.map(item => item.path)))
const filteredFiles = computed(() => files.value.filter(file => (!changedOnly.value || changedPaths.value.has(file.path)) && file.path.toLocaleLowerCase().includes(fileQuery.value.trim().toLocaleLowerCase())))
const filteredChanges = computed(() => changes.value.filter(item => item.path.toLocaleLowerCase().includes(fileQuery.value.trim().toLocaleLowerCase())))
function expandDiffs(open) { filesPanel.value?.querySelectorAll('.coding-diff').forEach(node => { node.open = open }) }
async function copyPreview() {
  const current = openedFile.value
  if (!current) return
  try { await navigator.clipboard.writeText(current.content); if (openedFile.value === current) fileNotice.value = '已复制文件内容' }
  catch { if (openedFile.value === current) fileNotice.value = '复制未完成，可在下方选中文件内容手动复制' }
}
const chatId = ref(''), chatHistory = ref([])
const conversation = ref(null), promptInput = ref(null), chatMessages = ref([]), chatEvents = ref([]), chatRunning = ref(false)
const pending = ref(null), fileEditing = ref(false), filePath = ref(''), fileContent = ref(''), fileSaving = ref(false)
const modelCatalog = ref({ profiles: [], providers: [], defaultModelId: 'system' }), selectedModels = ref({ work: 'system', chat: 'system' })
const selectedEfforts = ref({work:'',chat:''})
const modelPickerOpen = ref(false), modelsLoading = ref(true), modelsError = ref(''), capabilitiesLoading = ref(false)
const capabilitiesModelId = ref(''), chatModelSnapshot = ref(null)
let pollTimer = null, source = null, generation = 0, disposed = false, capabilityGeneration = 0, modelGeneration = 0
const policies = [
  { id: 'ask', label: '始终询问', detail: '每次调用工具前都需要确认' },
  { id: 'on-risk', label: '按需确认', detail: '修改文件和运行命令时确认' },
  { id: 'allow', label: '全部允许', detail: '允许在当前项目权限范围内使用工具' }
]
const policyLabel = computed(() => policies.find(item => item.id === policy.value)?.label || '按需确认')
const selectedProject = computed(() => projects.value.find(item => item.id === (session.value?.projectId || projectId.value)))
const isLocal = computed(() => selectedProject.value?.source === 'local')
const localDirectory = shallowRef(null), savedLocalHandle = shallowRef(null), localConnected = ref(false), localConnecting = ref(false)
let incomingRunner = null
try { if(location.hash.startsWith('#local-runner=')){ const value=location.hash.slice(14); history.replaceState(history.state,'',location.pathname+location.search); if(value.length<2048){const parsed=JSON.parse(decodeURIComponent(value)); new LocalRunner(parsed.url,parsed.token); incomingRunner=parsed} } } catch {}
const runner = shallowRef(null), runnerUrl = ref(''), runnerToken = ref(''), runnerBusy = ref(false), runnerJobs = ref([])
async function connectRunner() {
  if (running.value || !localDirectory.value) return
  runnerBusy.value=true; error.value=''
  const directory=localDirectory.value
  try { const value=await new LocalRunner(runnerUrl.value.trim(), runnerToken.value.trim()).pair(directory); if(directory!==localDirectory.value){value.disconnect();return} runner.value?.disconnect(); runner.value=value; runnerToken.value=''; await refreshProcesses() }
  catch(e){error.value=localError(e)}finally{runnerBusy.value=false}
}
async function refreshProcesses(){try{if(runner.value)runnerJobs.value=JSON.parse(await runner.value.execute('process_status',{}))}catch(e){error.value=localError(e)}}
async function stopProcess(id){try{await runner.value.execute('stop',{id});await refreshProcesses()}catch(e){error.value=localError(e)}}
const localError = e => e.response?.data?.message || e.message || '本机目录操作未完成'
const supportsLocal = localSupported()
const localClientId = globalThis.crypto?.randomUUID?.() || '', localReceipts = new Map()
let localPumping = false, localRestoreGeneration = 0
const ownerId = () => auth.user?.id || auth.user?.username
const codingBudget = computed(() => [...(session.value?.events || [])].reverse().find(e => e.type === 'context' && e.maxOutputTokens))
const workspaceLabel = computed(() => isLocal.value ? '本机目录' : '服务端工作区')
function disconnectLocal() { runner.value?.disconnect(); runner.value=null; runnerJobs.value=[]; runnerToken.value=''; localDirectory.value?.disconnect(); localDirectory.value = null; localConnected.value = false; savedLocalHandle.value = null; localReceipts.clear(); localRestoreGeneration++ }
async function restoreDirectory() {
  disconnectLocal(); const token = localRestoreGeneration, owner = ownerId(), project = selectedProject.value?.id
  if (!isLocal.value || !project) return
  if (policy.value === 'allow') policy.value = 'on-risk'
  if (executionMode.value === 'team') executionMode.value = 'auto'
  try {
    const handle = await restoreLocalHandle(owner, project)
    if (token !== localRestoreGeneration || disposed || owner !== ownerId()) return
    savedLocalHandle.value = handle || null
    if (handle && await handle.queryPermission({mode:'readwrite'}) === 'granted' && token === localRestoreGeneration && !disposed) { localDirectory.value = new LocalDirectory(handle); localConnected.value = true }
  } catch { error.value = '本机目录授权未能恢复，请重新选择目录。' }
}
async function addLocalProject() {
  if (projectBusy.value || running.value || !supportsLocal) return
  projectBusy.value = true; error.value = ''; const owner = ownerId()
  try {
    const handle = await window.showDirectoryPicker({ mode: 'readwrite', id: 'yangbin-coding-project' })
    if (disposed || owner !== ownerId()) return
    const validated = new LocalDirectory(handle); await validated.ready()
    const value = await codingApi.createProject(projectName.value.trim() || handle.name.slice(0,80), 'local')
    if (disposed || owner !== ownerId()) return
    await saveLocalHandle(owner, value.id, handle)
    projects.value.push(value); projectId.value = value.id; projectName.value = ''; projectOpen.value = false
  } catch (e) { if (e.name !== 'AbortError') error.value = localError(e) }
  finally { projectBusy.value = false }
}
async function connectLocal() {
  if (localConnecting.value || running.value || !isLocal.value || !supportsLocal || !savedLocalHandle.value) return
  localConnecting.value = true; error.value = ''; const owner = ownerId(), project = selectedProject.value.id, saved = savedLocalHandle.value
  try {
    // Invoke the permission request in the click handler, before unrelated awaits.
    const handle = saved ? (await saved.requestPermission({mode:'readwrite'}) === 'granted' ? saved : null) : await window.showDirectoryPicker({mode:'readwrite', id:'yangbin-coding-project'})
    if (!handle) throw new Error('未获得目录读写授权')
    if (disposed || owner !== ownerId() || project !== selectedProject.value?.id) return
    // A missing device binding cannot establish identity by matching a directory name.
    if (!saved) throw new Error('此浏览器没有该项目的目录绑定，请创建新的本机项目。')
    await saveLocalHandle(owner, project, handle)
    if (disposed || owner !== ownerId() || project !== selectedProject.value?.id) return
    localDirectory.value = new LocalDirectory(handle); savedLocalHandle.value = handle; localConnected.value = true
  } catch (e) { if (e.name !== 'AbortError') error.value = localError(e) }
  finally { localConnecting.value = false }
}
async function pumpLocalFiles() {
  if (!isLocal.value || !workRunning.value || localPumping || !localDirectory.value) return
  const current = session.value.id, turn = session.value.turns.at(-1)?.id, owner = ownerId(), directory = localDirectory.value
  // A restored page has a new client ID; old operations will never be executed again.
  localPumping = true
  const valid = () => !disposed && owner === ownerId() && localDirectory.value === directory && session.value?.id === current && session.value?.turns.at(-1)?.id === turn && workRunning.value
  try {
    const requests = await codingApi.localRequests(current, localClientId)
    for (const request of requests) {
      if (!valid() || request.turnId !== turn) return
      let result = localReceipts.get(request.requestId)
      if (!result) {
        if (request.claimed) throw new Error('文件操作结果未知，请停止任务并检查本机文件；不会重复执行。')
        const claimed = await codingApi.claimLocal(current, localClientId, request.requestId)
        if (!valid() || claimed.turnId !== turn || claimed.expiresAt <= Date.now()) return
        try { const output = claimed.operation === 'ping' && runner.value ? JSON.stringify({localProcesses:true,platform:runner.value.platform}) : ['start','preview','process_status','stop'].includes(claimed.operation) ? await runner.value.execute(claimed.operation, claimed.arguments, claimed.requestId) : await directory.execute(claimed.operation, claimed.arguments, valid); if(['start','preview','stop'].includes(claimed.operation))await refreshProcesses(); result = { clientId:localClientId, requestId:request.requestId, ok:true, output } }
        catch (e) { result = { clientId:localClientId, requestId:request.requestId, ok:false, output:String(e.message || '本机文件操作失败').slice(0,500) } }
        localReceipts.set(request.requestId, result)
      }
      if (!valid()) return
      await codingApi.completeLocal(current, result)
    }
  } catch (e) { if (valid()) error.value = localError(e) }
  finally { localPumping = false }
}
watch(localDirectory, directory => {if(directory && incomingRunner){runnerUrl.value=incomingRunner.url;runnerToken.value=incomingRunner.token;incomingRunner=null;void connectRunner()}})
watch(() => [ownerId(), selectedProject.value?.id], restoreDirectory)

const workRunning = computed(() => ['RUNNING', 'QUEUED', 'WAITING_APPROVAL', 'AWAITING_APPROVAL', 'CANCELLING'].includes(String(session.value?.status || '').toUpperCase()))
const running = computed(() => mode.value === 'chat' ? chatRunning.value : workRunning.value)
const effectiveModelId = computed(() => {
  if (mode.value === 'chat') return chatRunning.value ? chatModelSnapshot.value?.profileId || 'system' : selectedModels.value.chat
  return pending.value?.modelProfileId || (workRunning.value ? session.value?.turns?.at(-1)?.modelProfileId || 'system' : selectedModels.value.work)
})
const selectedProfile = computed(() => modelCatalog.value.profiles.find(profile => profile.id === effectiveModelId.value))
const selectedModelValid = computed(() => effectiveModelId.value === 'system' ? modelCatalog.value.systemEnabled !== false : Boolean(selectedProfile.value))
const effortLevels = computed(() => selectedProfile.value?.reasoningEfforts || [])
const modelLabel = computed(() => effectiveModelId.value === 'system' ? '系统默认' : selectedProfile.value?.name || session.value?.turns?.at(-1)?.modelSnapshot?.name || '模型不可用')
const workAvailable = computed(() => (!isLocal.value || localConnected.value) && selectedModelValid.value && selectedProfile.value?.codingSupported !== false && !modelsLoading.value && !capabilitiesLoading.value && capabilitiesModelId.value === effectiveModelId.value && capabilities.value?.available)
const hasContent = computed(() => mode.value === 'chat' ? chatMessages.value.length > 0 : Boolean(session.value))
const pendingApprovals = computed(() => {
  if (!workRunning.value) return []
  const currentTurn = session.value?.turns?.at(-1)?.id
  const events = (session.value?.events || []).filter(event => event.turnId === currentTurn)
  const resolved = new Set(events.filter(item => item.type === 'approval_resolved').map(item => item.approvalId))
  return events.filter(item => item.type === 'approval_required' && !resolved.has(item.approvalId))
})
const historyPanel = ref(null), projectPanel = ref(null), policyPanel = ref(null), modelPanel = ref(null), filesPanel = ref(null), editorPanel = ref(null), approvalPanel = ref(null)
const approvalsHidden = ref(false), fileBaseline = ref('')
const fileDirty = computed(() => fileEditing.value && JSON.stringify([filePath.value, fileContent.value]) !== fileBaseline.value)
const { allowDiscard: allowFileDiscard } = useUnsavedChanges(fileDirty, fileSaving, () => auth.user?.id || auth.user?.username)
const { allowDiscard: allowProjectDiscard } = useUnsavedChanges(computed(() => !!projectName.value), projectBusy, () => auth.user?.id || auth.user?.username)
const allowNavigation = async () => { if (isLocal.value && workRunning.value) { error.value = '本机目录任务需要保持此页面打开，请先停止任务再离开。'; return false } return await allowFileDiscard() && await allowProjectDiscard() }
onBeforeRouteLeave(allowNavigation)
onBeforeRouteUpdate((to, from) => to.query.session !== from.query.session ? allowNavigation() : true)
async function closeProject() { if (await allowProjectDiscard()) { projectName.value = ''; projectOpen.value = false } }
async function closeFiles() { if (await allowFileDiscard()) { fileEditing.value = false; filePanel.value = false } }
async function cancelFileEdit() { if (await allowFileDiscard()) fileEditing.value = false }
async function chooseProject(id) { if (!(await allowFileDiscard())) return; projectId.value = id; fileEditing.value = false; filePanel.value = false; projectOpen.value = false }
function promptKeys(event) { if (event.key === 'Enter' && !event.shiftKey && !event.ctrlKey && !event.metaKey && !event.altKey && !isComposing(event)) { event.preventDefault(); send() } }
function projectKeys(event) { if (event.key === 'Enter' && !isComposing(event)) { event.preventDefault(); createProject() } }
useOverlay(historyOpen, historyPanel, () => { historyOpen.value = false }, { modal: false })
useOverlay(projectOpen, projectPanel, closeProject, { modal: false })
useOverlay(policyOpen, policyPanel, () => { policyOpen.value = false }, { modal: false })
useOverlay(modelPickerOpen, modelPanel, () => { modelPickerOpen.value = false }, { modal: false })
useOverlay(filePanel, filesPanel, closeFiles, { modal: false })
useOverlay(fileEditing, editorPanel, cancelFileEdit, { modal: false })
useOverlay(() => pendingApprovals.value.length > 0 && !approvalsHidden.value, approvalPanel, () => { approvalsHidden.value = true }, { modal: false })
watch(() => pendingApprovals.value.map(a => a.approvalId).join(','), () => { approvalsHidden.value = false })
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
function restoreEffort(currentMode) { let value=''; try { value=sessionStorage.getItem(modelPreferenceKey(auth.user?.id || auth.user?.username,currentMode)+':effort:'+selectedModels.value[currentMode]) || '' } catch {} const levels=modelCatalog.value.profiles.find(p=>p.id===selectedModels.value[currentMode])?.reasoningEfforts||[]; selectedEfforts.value[currentMode]=levels.includes(value)?value:'' }
function chooseEffort(value) { selectedEfforts.value[mode.value]=value; try {sessionStorage.setItem(modelPreferenceKey(auth.user?.id || auth.user?.username,mode.value)+':effort:'+selectedModels.value[mode.value],value)}catch{} }
function chooseModel(profileId) { if (running.value || busy.value || pending.value) return; selectedModels.value[mode.value] = profileId; rememberModel(mode.value); restoreEffort(mode.value); modelPickerOpen.value = false }
function applyModelCatalog(value, initialize = false) {
  modelCatalog.value = value
  for (const currentMode of ['work', 'chat']) {
    let choice = selectedModels.value[currentMode]
    if (initialize) { try { choice = sessionStorage.getItem(modelPreferenceKey(auth.user?.id || auth.user?.username, currentMode)) || value.defaultModelId || '' } catch { choice = value.defaultModelId || '' } }
    if (choice === 'system' ? value.systemEnabled === false : !value.profiles.some(profile => profile.id === choice)) choice = ''
    selectedModels.value[currentMode] = choice; rememberModel(currentMode); restoreEffort(currentMode)
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
  if (!selectedModelValid.value) { capabilities.value={available:false,reason:'所选模型已停用，请重新选择或前往总设置。'}; capabilitiesLoading.value=false; return }
  const choice = effectiveModelId.value, owner = auth.user?.id || auth.user?.username, token = ++capabilityGeneration
  capabilitiesLoading.value = true
  try { const value = await codingApi.capabilities(choice); if (disposed || token !== capabilityGeneration || owner !== (auth.user?.id || auth.user?.username)) return; capabilities.value = value; capabilitiesModelId.value = choice }
  catch { if (token === capabilityGeneration) { capabilities.value = { available: false, reason: '当前模型的 Coding Agent 状态不可用，请检查配置后重试。' }; capabilitiesModelId.value = choice } }
  finally { if (token === capabilityGeneration) capabilitiesLoading.value = false }
}
function historyModel(turn) { const value = turn.modelSnapshot; return value?.model ? `${value.name || value.provider || '模型'} · ${value.model}` : turn.modelProfileId ? (turn.modelProfileId === 'system' ? '系统默认' : '个人模型') : '历史记录未保存模型信息' }
function openModelManager() { modelPickerOpen.value=false; router.push('/settings') }
function statusLabel(value) {
  return ({ IDLE: '准备就绪', RUNNING: '执行中', QUEUED: '排队中', WAITING_APPROVAL: '等待确认', AWAITING_APPROVAL: '等待确认', SUCCEEDED: '已完成', COMPLETED: '已完成', FAILED: '执行失败', PARTIAL: '部分完成', BLOCKED: '受阻', CANCELLED: '已停止', INTERRUPTED: '已中断', CANCELLING: '正在停止' })[String(value || '').toUpperCase()] || value || '准备就绪'
}
const pretty = value => typeof value === 'string' ? value : JSON.stringify(value, null, 2)
const date = value => value ? new Date(value).toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' }) : ''
const id = () => globalThis.crypto?.randomUUID?.() || 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, char => { const r = Math.random() * 16 | 0; return (char === 'x' ? r : (r & 3 | 8)).toString(16) })
const pendingKey = sessionId => `coding-pending:${auth.user?.id || auth.user?.username}:${sessionId}`
function rememberPending() { if (session.value) { if (pending.value) sessionStorage.setItem(pendingKey(session.value.id), JSON.stringify(pending.value)); else sessionStorage.removeItem(pendingKey(session.value.id)) } }
function clearPendingIfAccepted() {
  if (pending.value && session.value?.turns?.some(turn => turn.id === pending.value.requestId || turn.requestId === pending.value.requestId)) { pending.value = null; rememberPending(); writeWorkspace('coding', codingDraftKey(), '', workspaceAccount); draft.value = '' }
}
async function scrollBottom(force = false) { const area = conversation.value; const near = !area || area.scrollHeight - area.scrollTop - area.clientHeight < 130; await nextTick(); if (conversation.value && (force || near)) conversation.value.scrollTop = conversation.value.scrollHeight }
function applySession(value) { session.value = value; writeWorkspace('coding', 'last', { session: value.id }); projectId.value = value.projectId; policy.value = pending.value?.approvalMode || value.approvalMode || policy.value; clearPendingIfAccepted(); scrollBottom() }
function stopPoll() { clearTimeout(pollTimer); pollTimer = null }
function schedulePoll() { stopPoll(); if (!disposed && session.value && workRunning.value) pollTimer = setTimeout(refreshSession, 1200) }
async function refreshSession() {
  const current = session.value?.id, token = generation
  if (!current) return
  try { const value = await codingApi.session(current); if (disposed || token !== generation || session.value?.id !== current) return; applySession(value); error.value = ''; await pumpLocalFiles(); if (!workRunning.value) { await loadHistory(); if (filePanel.value) await loadFiles() } }
  catch (e) { if (token === generation) error.value = '状态更新暂时中断，正在重连；不会自动重复提交任务。' }
  finally { if (token === generation) schedulePoll() }
}
async function loadHistory() { const owner = auth.user?.id || auth.user?.username; const value = await codingApi.sessions(); if (!disposed && owner === (auth.user?.id || auth.user?.username)) sessions.value = value }
async function loadSession(sessionId) {
  saveWorkspace(); restoringWorkspace = true; const token = ++generation; stopPoll(); busy.value = false; session.value = null; pending.value = null; openedFile.value = null; changes.value = []; files.value = []; filePanel.value = false; fileEditing.value = false; fileContent.value = ''; error.value = ''; draft.value = ''
  if (!sessionId) { writeWorkspace('coding', 'last', null); restoreWorkspace(); return }
  loading.value = true
  try {
    const value = await codingApi.session(sessionId)
    if (disposed || token !== generation) return
    try { pending.value = JSON.parse(sessionStorage.getItem(pendingKey(sessionId)) || 'null') } catch { pending.value = null }
    applySession(value); if (pending.value) { pending.value = { revision: value.revision, approvalMode: value.approvalMode, modelProfileId: 'system', ...pending.value }; draft.value = pending.value.prompt; rememberPending() }
    scrollBottom(true); schedulePoll()
  } catch (e) { if (token === generation) error.value = isLocal.value ? localError(e) : errorMessage(e) }
  finally { if (token === generation) { loading.value = false; restoreWorkspace(); if (filePanel.value) loadFiles() } }
}
function closeChat() { source?.close(); source = null; clearTimeout(legacyPoll); legacyPoll = null; chatRunning.value = false }
async function recoverChat(cid = chatId.value) {
  if (!cid || disposed || mode.value !== 'chat') return
  const token = generation, owner = auth.user?.id || auth.user?.username
  try { const {data} = await api.get('/ai/manus/conversations/' + encodeURIComponent(cid)); if (disposed || token !== generation || cid !== chatId.value || owner !== (auth.user?.id || auth.user?.username)) return; chatMessages.value = data.turns.flatMap(t => [{id:t.id+'u',role:'user',text:t.prompt},{id:t.id+'a',role:'assistant',text:t.answer || (t.status === 'RUNNING' ? '' : '任务状态：' + statusLabel(t.status))}]); chatRunning.value = data.turns.at(-1)?.status === 'RUNNING'; error.value = chatRunning.value ? '任务仍在服务端执行，正在同步状态。' : ''; if (chatRunning.value) legacyPoll = setTimeout(() => recoverChat(cid), 2000) } catch { if (!disposed && token === generation) { error.value = '历史状态暂时不可用，正在重连；不会重新提交。'; legacyPoll = setTimeout(() => recoverChat(cid), 2500) } }
}
async function newTask(nextMode = mode.value) { if (busy.value || running.value || !(await allowNavigation())) return; fileEditing.value = false; projectName.value = ''; closeChat(); chatId.value = ''; chatHistory.value = []; chatMessages.value = []; chatEvents.value = []; draft.value = ''; error.value = ''; historyOpen.value = false; router.push({ path: nextMode === 'chat' ? '/super-agent' : '/coding', query: {} }) }
function selectMode(nextMode) { if (nextMode !== mode.value) newTask(nextMode) }
function openSession(item) { if (busy.value) return; historyOpen.value = false; router.push({ path: '/coding', query: { session: item.id } }) }
async function createProject() {
  if (!projectName.value.trim() || projectBusy.value) return
  projectBusy.value = true; error.value = ''
  try { const value = await codingApi.createProject(projectName.value.trim()); projects.value.push(value); projectId.value = value.id; projectName.value = ''; projectOpen.value = false }
  catch (e) { error.value = isLocal.value ? localError(e) : errorMessage(e) } finally { projectBusy.value = false }
}
async function send() {
  if (!pending.value && (!selectedModelValid.value || modelsLoading.value || modelsError.value)) return
  if (!draft.value.trim() || busy.value || running.value) return
  if (mode.value === 'chat') { sendChat(); return }
  if (!workAvailable.value || !projectId.value) return
  if (isLocal.value && (executionMode.value === 'team' || policy.value === 'allow')) { error.value = '本机目录请选择单 Agent 模式及按需确认或始终询问。'; return }
  if (isLocal.value && !pending.value) { localDirectory.value.observed.clear(); localDirectory.value.changes.clear(); localReceipts.clear() }
  busy.value = true; error.value = ''; const token = generation
  try {
    if (!session.value) {
      const created = await codingApi.createSession({ projectId: projectId.value, title: draft.value.trim().slice(0, 60), approvalMode: policy.value })
      if (token !== generation || disposed) return
      applySession(created); writeWorkspace('coding', 'draft:new', '', workspaceAccount)
      await router.replace({ path: '/coding', query: { session: created.id } })
    }
    const currentId = session.value.id
    if (!pending.value) pending.value = { requestId: id(), prompt: draft.value.trim(), executionMode: executionMode.value, ...(isLocal.value ? {localClientId} : {}), revision: session.value.revision, approvalMode: policy.value, modelProfileId: effectiveModelId.value, reasoningEffort: selectedEfforts.value.work || null }
    rememberPending()
    const value = await codingApi.turn(currentId, { ...pending.value })
    if (disposed || session.value?.id !== currentId) return
    applySession(value); pending.value = null; rememberPending(); draft.value = ''; await loadHistory(); scrollBottom(true); schedulePoll()
  } catch (e) {
    if (e.response?.status >= 400 && e.response?.status < 500) { pending.value = null; rememberPending(); error.value = isLocal.value ? localError(e) : errorMessage(e) }
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
    chatRunning.value = data.turns.at(-1)?.status === 'RUNNING'; error.value=chatRunning.value?'任务仍在服务端执行，正在同步状态。':''; if(chatRunning.value) { clearTimeout(legacyPoll); legacyPoll=setTimeout(()=>recoverChat(cid),1500) }
    router.replace({path:'/super-agent',query:{chat:cid}});scrollBottom(true)
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
  router.replace({path:'/super-agent',query:{chat:chatId.value}});
  chatRunning.value = true; source = chatWithManus(prompt, profileId, chatId.value, id(), selectedEfforts.value.chat || null); const connection = source
  connection.onmessage = event => { if (source !== connection) return; if (event.data === '[DONE]') closeChat(); else { response.text += (response.text ? '\n\n' : '') + event.data; scrollBottom() } }
  connection.addEventListener('trace', event => { if (source !== connection) return; try { const value = JSON.parse(event.data); const index = chatEvents.value.findIndex(item => item.id === value.id); if (index < 0) chatEvents.value.push(value); else chatEvents.value[index] = value } catch { /* Non-JSON trace cannot be rendered. */ } })
  connection.addEventListener('failure', event => { if (source === connection) { error.value = event.data || '对话未完成'; closeChat() } })
  connection.onerror = () => { if (source === connection) { error.value = '连接已中断，正在恢复历史状态。'; closeChat(); chatRunning.value = true; legacyPoll = setTimeout(() => recoverChat(), 1200); checkSession() } }
  scrollBottom(true)
}
async function decide(approvalId, approved) {
  if (busy.value) return
  busy.value = true; const currentId = session.value.id
  try { const value = await codingApi.approve(currentId, approvalId, approved); if (session.value?.id === currentId) { applySession(value); error.value = ''; schedulePoll() } }
  catch (e) { error.value = isLocal.value ? localError(e) : errorMessage(e) } finally { busy.value = false }
}
async function cancel() {
  if (mode.value === 'chat') { const cid=chatId.value; closeChat(); try { if(cid) await api.post('/ai/manus/conversations/'+cid+'/cancel'); error.value = '已请求停止后续工具操作；已发出的供应商请求可能仍在运行。'; if(cid) recoverChat(cid) } catch(e) { error.value=errorMessage(e) } return }
  if (!session.value || busy.value) return
  busy.value = true; const currentId = session.value.id
  try { const value = await codingApi.cancel(currentId); if (session.value?.id === currentId) { applySession(value); error.value = ''; schedulePoll() } }
  catch (e) { error.value = isLocal.value ? localError(e) : errorMessage(e) } finally { busy.value = false }
}
async function loadFiles() {
  const currentId = session.value?.id, project = session.value?.projectId || projectId.value, owner = ownerId(), token = ++filesSequence, directory = localDirectory.value, local = isLocal.value
  if (!project) return
  const valid = () => !disposed && token === filesSequence && owner === ownerId() && (session.value?.projectId || projectId.value) === project && session.value?.id === currentId && (!local || localDirectory.value === directory)
  filesLoading.value = true; filesError.value = ''
  try {
    if (local && !directory) throw new Error('本机目录尚未连接，请先重新授权目录。')
    const results = local ? [await directory.files(), [...directory.changes.values()]] : await Promise.all([codingApi.files(project), currentId ? codingApi.diff(currentId) : Promise.resolve([])])
    if (!valid()) return
    files.value = results[0]; changes.value = results[1]
  } catch (e) { if (valid()) filesError.value = local ? localError(e) : errorMessage(e) }
  finally { if (token === filesSequence) filesLoading.value = false }
}
async function showFiles() { if (filePanel.value) { await closeFiles(); return } filePanel.value = true; await loadFiles() }
async function editFile(file = null) { if (!(await allowFileDiscard())) return; filePath.value = file?.path || ''; fileContent.value = file?.content || ''; fileBaseline.value = JSON.stringify([filePath.value, fileContent.value]); fileEditing.value = true }
async function saveFile() {
  if (!filePath.value.trim() || fileSaving.value || workRunning.value) return
  const project = session.value?.projectId || projectId.value; fileSaving.value = true; error.value = ''
  try { const value = isLocal.value ? (await localDirectory.value.write(filePath.value.trim(), fileContent.value), {path:filePath.value.trim(),content:fileContent.value}) : await codingApi.putFile(project, filePath.value.trim(), fileContent.value); if ((session.value?.projectId || projectId.value) === project) { openedFile.value = value; fileEditing.value = false; await loadFiles() } }
  catch (e) { error.value = isLocal.value ? localError(e) : errorMessage(e) } finally { fileSaving.value = false }
}
async function openFile(path) {
  const project = session.value?.projectId || projectId.value, owner = ownerId(), token = ++previewSequence, directory = localDirectory.value, local = isLocal.value
  const valid = () => !disposed && token === previewSequence && owner === ownerId() && (session.value?.projectId || projectId.value) === project && (!local || localDirectory.value === directory)
  previewLoading.value = true; previewError.value = ''; fileNotice.value = ''; openedFile.value = null
  try {
    if (local && !directory) throw new Error('本机目录尚未连接，请先重新授权目录。')
    const value = local ? {path,content:await directory.read(path, {observe: !workRunning.value})} : await codingApi.file(project, path)
    if (valid()) openedFile.value = value
  } catch (e) { if (valid()) previewError.value = local ? localError(e) : errorMessage(e) }
  finally { if (token === previewSequence) previewLoading.value = false }
}
watch(() => [ownerId(), selectedProject.value?.id, session.value?.id, mode.value], () => {
  filesSequence++; previewSequence++; filesLoading.value = false; previewLoading.value = false
  fileQuery.value = ''; changedOnly.value = false; filesError.value = ''; previewError.value = ''; fileNotice.value = ''; files.value = []; changes.value = []; openedFile.value = null
})
watch(effectiveModelId, () => { modelPickerOpen.value = false; if (mode.value === 'work') refreshCapabilities() })
watch(() => route.query.chat, value => { if (mode.value !== 'chat' || value === chatId.value) return; closeChat(); if (typeof value === 'string') openChat(value); else { generation++; chatId.value = ''; chatMessages.value = []; chatEvents.value = [] } })
watch(() => route.query.session, value => { if (value !== session.value?.id) loadSession(typeof value === 'string' ? value : null) })
watch(() => mode.value, () => { modelPickerOpen.value = false; generation++; closeChat(); chatMessages.value = []; chatEvents.value = []; chatId.value=''; chatHistory.value=[]; draft.value = ''; policyOpen.value = false; error.value = ''; filePanel.value = false; if (mode.value === 'chat') { stopPoll(); session.value = null; pending.value = null } else refreshCapabilities() })
watch(() => auth.user?.id || auth.user?.username, (_, previousOwner) => { disconnectLocal(); saveWorkspace(); restoringWorkspace = true; workspaceAccount = auth.user?.id || auth.user?.username; for (const currentMode of ['work','chat']) { try { sessionStorage.removeItem(modelPreferenceKey(previousOwner, currentMode)) } catch {} } modelGeneration++; capabilityGeneration++; modelPickerOpen.value = false; modelCatalog.value = { profiles: [], providers: [], defaultModelId: 'system' }; selectedModels.value = { work: 'system', chat: 'system' }; selectedEfforts.value={work:'',chat:''}; executionMode.value='auto'; chatModelSnapshot.value = null; capabilitiesModelId.value = ''; generation++; closeChat(); stopPoll(); session.value = null; sessions.value = []; projects.value = []; draft.value = ''; pending.value = null; chatMessages.value = []; chatEvents.value = []; chatId.value=''; chatHistory.value=[]; projectId.value = ''; projectName.value = ''; openedFile.value = null; files.value = []; changes.value = []; filePanel.value = false; fileEditing.value = false; fileContent.value = ''; projectOpen.value = false; historyOpen.value = false; policyOpen.value = false; capabilities.value = null; restoringWorkspace = false })
onMounted(async () => {
  const owner = auth.user?.id || auth.user?.username
  const results = await Promise.allSettled([codingApi.capabilities(), codingApi.projects(), codingApi.sessions(), modelApi.list()])
  if (disposed || owner !== (auth.user?.id || auth.user?.username)) return
  if (results[0].status === 'fulfilled') capabilities.value = results[0].value
  else capabilities.value = { available: false, reason: 'Coding Agent 服务暂不可用，请稍后刷新。' }
  if (results[1].status === 'fulfilled') { projects.value = [...new Map([...results[1].value, ...projects.value].map(project => [project.id, project])).values()]; if (!projectId.value) { const stored = route.query.project || readWorkspace('coding', 'project', ''); projectId.value = projects.value.some(p => p.id === stored) ? stored : projects.value[0]?.id || '' } }
  if (results[2].status === 'fulfilled') sessions.value = results[2].value
  if (results[3].status === 'fulfilled') applyModelCatalog(results[3].value, true)
  else modelsError.value = '模型配置未能加载，请重试。'
  modelsLoading.value = false; refreshCapabilities()
  loading.value = false
  restoreWorkspace()
  if (typeof route.query.session === 'string') await loadSession(route.query.session)
  else if (mode.value === 'work') { writeWorkspace('coding', 'last', null); if (filePanel.value) await loadFiles() }
  if(mode.value==='chat'&&typeof route.query.chat==='string')await openChat(route.query.chat)
})
watch([draft, filePanel, projectId], saveWorkspace, { flush: 'sync' })
function warnLocalClose(event) { if (isLocal.value && workRunning.value) { event.preventDefault(); event.returnValue = '' } }
onMounted(() => window.addEventListener('beforeunload', warnLocalClose))
onBeforeUnmount(() => { window.removeEventListener('beforeunload', warnLocalClose); disconnectLocal(); saveWorkspace(); disposed = true; modelGeneration++; capabilityGeneration++; generation++; closeChat(); stopPoll() })
</script>

<template>
  <main class="coding-workspace" data-testid="coding-workspace">
    <header class="coding-topbar">
      <div class="coding-location"><StudioIcon :name="mode === 'work' ? 'layout' : 'chat'" :size="19" /><strong>{{ mode === 'work' ? 'Coding Agent' : '历史智能体对话' }}</strong><span v-if="session" class="coding-project-label">/ {{ selectedProject?.name || '项目' }}</span></div>
      <div class="coding-top-actions"><CodingEditorLink v-if="mode === 'work' && selectedProject" :project="selectedProject" :owner="ownerId()" :directory-name="savedLocalHandle?.name" /><button v-if="mode === 'work' && projectId" class="quiet-button" aria-label="项目长期记忆" title="项目长期记忆" @click="memoryOpen = true" data-testid="coding-memory-open"><StudioIcon name="notes" :size="18" /><span>项目记忆</span></button><RouterLink class="quiet-button" to="/governance" aria-label="用量与运行状态" title="用量与运行状态"><StudioIcon name="chart" :size="18" /><span>用量与运行状态</span></RouterLink><button class="quiet-button" :aria-label="mode === 'chat' ? '对话历史' : '历史任务'" title="历史记录" @click="showHistory" data-testid="coding-history"><StudioIcon name="clock" :size="18" /><span>{{ mode === 'chat' ? '对话历史' : '历史任务' }}</span></button><button v-if="mode === 'work' && (session || projectId)" class="quiet-button" aria-label="项目文件" title="项目文件" @click="showFiles" data-testid="coding-files"><StudioIcon name="folder" :size="18" /><span>项目文件</span></button><button v-if="hasContent" class="quiet-button" aria-label="新任务" title="新任务" :disabled="busy" @click="newTask()"><StudioIcon name="plus" :size="18" /><span>新任务</span></button></div>
    </header>
    <CodingMemory v-if="memoryOpen && mode === 'work' && projectId" :project-id="session?.projectId || projectId" :project-name="selectedProject?.name" :source-session-id="session?.id" :source-turn-id="session?.turns?.at(-1)?.id" @close="memoryOpen = false" />
    <div v-if="historyOpen" ref="historyPanel" role="dialog" aria-label="任务历史" class="coding-history-panel" data-testid="coding-history-panel"><div class="panel-heading"><h2>{{ mode === 'chat' ? '对话历史' : '工作任务' }}</h2><button class="icon-button" aria-label="关闭历史任务" @click="historyOpen = false"><StudioIcon name="close" :size="18" /></button></div><p v-if="!(mode === 'chat' ? chatHistory : sessions).length" class="muted">开始一个任务后，可以在这里继续。</p><button v-for="item in (mode === 'chat' ? chatHistory : sessions)" :key="item.id" class="history-row" :disabled="running" @click="mode === 'chat' ? openChat(item.id) : openSession(item)"><strong>{{ item.title || '未命名任务' }}</strong><span>{{ statusLabel(item.status) }} · {{ date(item.updatedAt || item.createdAt) }}</span></button><button v-if="mode === 'chat' && chatId && !running" class="quiet-button" @click="deleteChat(chatId)">删除当前对话</button></div>
    <div class="coding-body" :class="{ 'with-files': filePanel }">
      <section ref="conversation" class="coding-conversation" aria-label="任务内容" data-testid="coding-scroll">
        <div v-if="!hasContent" class="coding-welcome">
          <div class="coding-orbit" aria-hidden="true"><span></span></div>
          <h1>{{ mode === 'work' ? '在项目中，把想法做出来' : '今天想聊些什么？' }}</h1>
          <p v-if="mode === 'chat'" class="coding-service-note">这是旧版智能体对话，保留原有历史和执行方式。<RouterLink to="/assistant?mode=chat">打开通用助手</RouterLink></p>
          <p class="coding-mode-description">{{ mode === 'work' ? '查看文件、修改代码、审阅差异，也可以整理项目研究与技术文档。' : '和 历史智能体对话交流、探索与解决问题。' }}</p>
          <div class="coding-suggestions"><p>为你推荐</p><button v-for="item in suggestions" :key="item.title" @click="draft = item.prompt; promptInput?.focus()"><StudioIcon :name="item.icon" :size="19" /><span>{{ item.title }}</span><StudioIcon name="arrow" :size="16" /></button></div>
          <div v-if="mode === 'work' && sessions.length" class="coding-recent"><div class="recent-heading"><span>最近的编程任务</span><button @click="historyOpen = true">查看全部</button></div><button v-for="item in sessions.slice(0, 3)" :key="item.id" @click="openSession(item)"><span>{{ item.title || '未命名任务' }}</span><small>{{ statusLabel(item.status) }}</small></button></div>
        </div>
        <div v-else class="coding-thread">
          <template v-if="mode === 'work'">
            <div class="thread-heading"><h1>{{ session.title || '工作任务' }}</h1><span class="status-chip" :class="{ running: workRunning }" role="status">{{ statusLabel(session.status) }}</span></div>
            <p v-if="!turns.length" class="muted">工作区已准备好，发送消息开始。</p>
            <article v-for="turn in turns" :key="turn.id" class="coding-turn" :data-turn="turn.id"><div class="coding-user-message">{{ turn.prompt }}</div><div class="coding-agent-label"><span class="agent-mini">✦</span> Coding Agent <span class="turn-model" :title="historyModel(turn)">{{ historyModel(turn) }}</span><small>{{ statusLabel(turn.status) }}</small></div><div v-if="turn.events.length" class="coding-tool-events"><details v-for="event in turn.events" :key="event.seq" class="coding-tool-event" :class="{ 'tool-error': event.type === 'error' || event.ok === false }"><summary><StudioIcon :name="event.type === 'error' || event.ok === false ? 'close' : event.type === 'tool_end' ? 'check' : 'notes'" :size="15" /><span>{{ event.type === 'error' ? event.message : `${event.type === 'tool_start' ? '调用' : event.ok === false ? '失败' : '完成'} ${event.toolName || '工具'}` }}</span></summary><pre>{{ pretty(event.arguments ?? event.output ?? event.error ?? event.result ?? event.message) }}</pre></details></div><CodingTeam :session-id="session.id" :turn-id="turn.id" :events="session.events" :session-status="session.status" @updated="applySession"/><ReplyMarkdown v-if="turn.text" :text="turn.text" /><p v-else-if="['RUNNING','QUEUED'].includes(String(turn.status).toUpperCase())" class="coding-thinking"><span></span>正在处理任务…</p></article>
            <button v-if="pendingApprovals.length && approvalsHidden" class="quiet-button" data-testid="reopen-approvals" @click="approvalsHidden = false">查看待审批操作（{{ pendingApprovals.length }}）</button><div v-if="pendingApprovals.length && !approvalsHidden" ref="approvalPanel" role="region" aria-label="待审批操作"><button class="quiet-button" aria-label="收起审批详情" @click="approvalsHidden = true">收起详情（任务继续等待审批）</button><section v-for="approval in pendingApprovals" :key="approval.approvalId" class="coding-approval" data-testid="coding-approval"><div><StudioIcon name="check" :size="20" /><h2>需要你的确认</h2></div><p>{{ approval.reason || '智能体请求执行以下操作。' }}</p><strong>{{ approval.toolName }}</strong><pre>{{ pretty(approval.arguments) }}</pre><footer><button class="quiet-button" :disabled="busy" @click="decide(approval.approvalId, false)" data-testid="approval-deny">拒绝</button><button class="coding-primary" :disabled="busy" @click="decide(approval.approvalId, true)" data-testid="approval-allow">允许这次操作</button></footer></section></div>
          </template>
          <template v-else><article v-for="message in chatMessages" :key="message.id" class="coding-turn"><div v-if="message.role === 'user'" class="coding-user-message">{{ message.text }}</div><template v-else><div class="coding-agent-label"><span class="agent-mini">✦</span> 历史智能体对话<span class="turn-model">{{ message.modelSnapshot?.model || message.modelSnapshot?.name }}</span></div><ReplyMarkdown v-if="message.text" :text="message.text" /><p v-else-if="chatRunning" class="coding-thinking"><span></span>正在思考…</p></template></article><details v-if="chatEvents.length" class="coding-tool-event"><summary>本轮工具执行记录 · {{ chatEvents.length }}</summary><pre>{{ pretty(chatEvents) }}</pre></details></template>
        </div>
      </section>
      <aside v-if="filePanel" ref="filesPanel" aria-label="项目文件" class="coding-file-panel" data-testid="coding-file-panel"><div class="panel-heading"><h2>项目文件</h2><button class="icon-button" aria-label="关闭文件面板" @click="closeFiles"><StudioIcon name="close" :size="18" /></button></div><p class="muted">{{ selectedProject?.name }} · {{ workspaceLabel }}</p><div class="file-panel-actions"><button class="quiet-button" :disabled="filesLoading" @click="loadFiles" data-testid="refresh-files"><StudioIcon name="clock" :size="15" />{{ filesLoading ? '正在刷新…' : '刷新文件' }}</button><button class="quiet-button" :disabled="workRunning" @click="editFile()" data-testid="new-file"><StudioIcon name="plus" :size="15" />新建文件</button></div><div v-if="fileEditing" ref="editorPanel" role="dialog" aria-label="编辑项目文件" class="coding-file-editor"><label>项目相对路径<input v-model="filePath" placeholder="例如 src/index.js" aria-label="文件路径" :disabled="fileSaving" /></label><label>文件内容<textarea v-model="fileContent" rows="12" aria-label="文件内容" :disabled="fileSaving"></textarea></label><footer><button class="quiet-button" :disabled="fileSaving" @click="cancelFileEdit">取消</button><button class="coding-primary" :disabled="fileSaving || !filePath.trim() || workRunning" @click="saveFile" data-testid="save-file">保存文件</button></footer></div><div class="file-browser-tools">
          <label class="file-search"><StudioIcon name="search" :size="16" /><input v-model="fileQuery" type="search" aria-label="搜索项目文件" placeholder="按文件名或路径搜索" data-testid="file-search" /></label>
          <label class="changed-filter"><input v-model="changedOnly" type="checkbox" data-testid="changed-only" />只看已改文件</label>
          <p class="muted file-count" role="status">显示 {{ filteredFiles.length }} / {{ files.length }} 个已加载文件 · {{ changes.length }} 项改动</p>
        </div>
        <p v-if="filesLoading" class="muted" role="status">正在读取项目文件…</p>
        <p v-if="filesError" class="file-panel-error" role="alert">{{ filesError }}<span v-if="files.length">（当前显示上次加载的文件列表）</span></p>
        <p v-if="!filesLoading && !filesError && !files.length" class="muted file-empty">项目中暂无可显示的文件</p>
        <p v-else-if="!filesLoading && files.length && !filteredFiles.length" class="muted file-empty">{{ changedOnly ? '没有符合条件的已改文件，已删除文件请查看下方变更。' : '没有匹配的文件，试试其他关键词。' }}</p>
        <button v-for="file in filteredFiles" :key="file.path" class="coding-file-row" :class="{ active: openedFile?.path === file.path }" :title="file.path" @click="openFile(file.path)"><StudioIcon name="document" :size="16" /><span>{{ file.path }}</span><small v-if="changedPaths.has(file.path)" class="file-changed-badge">已改</small></button>
        <p v-if="previewLoading" role="status" class="muted">正在读取文件内容…</p>
        <p v-if="previewError" role="alert" class="file-panel-error">{{ previewError }}</p>
        <details v-if="openedFile" class="coding-file-preview" open><summary>{{ openedFile.path }}</summary><div class="file-preview-actions"><button class="quiet-button" :disabled="workRunning" @click="editFile(openedFile)">编辑文件</button><button class="quiet-button" @click="copyPreview" data-testid="copy-file-content"><StudioIcon name="copy" :size="15" />复制内容</button></div><p v-if="fileNotice" role="status">{{ fileNotice }}</p><pre tabindex="0" aria-label="文件内容预览">{{ openedFile.content }}</pre></details>
        <div class="file-changes-heading"><h3>{{ isLocal ? '当前页面文件变更' : '最近一轮文件变更' }} <span>{{ changes.length }}</span></h3><div v-if="filteredChanges.length"><button class="quiet-button" @click="expandDiffs(true)" data-testid="expand-diffs">展开全部</button><button class="quiet-button" @click="expandDiffs(false)" data-testid="collapse-diffs">收起全部</button></div></div>
        <p v-if="isLocal" class="muted file-scope-note">此处只记录当前页面的改动；刷新页面后记录清空，磁盘上的修改仍保留。</p>
        <p v-if="!changes.length" class="muted">暂无已记录的文件变更</p><p v-else-if="!filteredChanges.length" class="muted">没有匹配搜索条件的变更</p>
        <details v-for="change in filteredChanges" :key="change.path" class="coding-diff"><summary>{{ ({created:'新增',modified:'修改',deleted:'删除',added:'新增'})[change.kind] || change.kind }} · {{ change.path }}</summary><label>修改前</label><pre tabindex="0">{{ change.before ?? '（文件不存在）' }}</pre><label>修改后</label><pre tabindex="0">{{ change.after ?? '（文件已删除）' }}</pre></details></aside>
    </div>
    <footer class="coding-composer-zone">
      <p v-if="error" class="coding-error" role="alert">{{ error }} <button v-if="mode === 'work' && session" @click="refreshSession">刷新状态</button></p>
      <p v-if="modelsError" class="coding-service-note" role="status">{{ modelsError }} <button class="model-note-action" @click="reloadModels()">重试</button></p><p v-if="mode === 'work' && !modelsLoading && !capabilitiesLoading && capabilities && !capabilities.available" class="coding-service-note" role="status">{{ capabilities.reason || 'Coding Agent 暂未就绪' }} <button class="model-note-action" :disabled="running || busy || Boolean(pending)" @click="openModelManager">配置模型</button></p>
      <p v-if="mode === 'work' && isLocal" class="coding-service-note" data-testid="local-status">{{ localConnected ? '已连接本机目录；修改直接写回原文件。任务期间请保持页面打开；连接本机运行器后可以启动、预览和停止程序。' : '本机目录未连接；请在原浏览器重新授权。其他设备请新建本机项目。' }}<button v-if="!localConnected && savedLocalHandle" type="button" :disabled="running || localConnecting" @click="connectLocal" data-testid="connect-local">重新授权目录</button></p>
      <p v-if="mode === 'work' && codingBudget" class="coding-service-note">本轮单次输出上限 {{ codingBudget.maxOutputTokens.toLocaleString() }} Token（含推理），上下文 {{ codingBudget.contextWindow.toLocaleString() }} Token。</p>
      <details v-if="mode === 'work' && isLocal && localConnected" class="coding-service-note" data-testid="local-runner-panel">
        <summary>{{ runner ? '本机运行器已连接 · ' + runner.workspace : '连接本机运行器，启动项目' }}</summary>
        <p><a href="/downloads/yangbin-local-runner.zip" download>下载本机运行器</a>，解压后双击 start.cmd，填写项目文件夹路径并打开连接链接（需要 Node.js 22 或更新版本）。也可以在源码目录运行：<code>node coding-agent/src/local-runner.mjs "项目完整路径"</code>，然后填写显示的地址和连接码。浏览器询问访问本地网络时，请允许连接。命令以你的电脑账号运行，可修改本机文件；每次启动命令前均会展示内容并请求确认。</p>
        <label>本机地址<input v-model="runnerUrl" placeholder="http://127.0.0.1:端口" :disabled="running" /></label>
        <label>连接码<input v-model="runnerToken" type="password" autocomplete="off" :disabled="running" /></label>
        <button type="button" @click="connectRunner" :disabled="running || runnerBusy">连接运行器</button>
        <button v-if="runner" type="button" @click="refreshProcesses">刷新运行状态</button>
        <div v-for="job in runnerJobs" :key="job.id">
          <span>{{ job.state }} · {{ job.command || '网页预览' }}</span>
          <a v-if="job.url && /^http:\/\/127\.0\.0\.1:\d+\//.test(job.url)" :href="job.url" target="_blank" rel="noopener noreferrer">打开运行结果</a>
          <button v-if="job.state === 'RUNNING'" type="button" @click="stopProcess(job.id)">停止</button>
          <pre v-if="job.output">{{ job.output }}</pre>
        </div>
      </details>
      <form class="coding-composer" @submit.prevent="send" data-testid="coding-composer">
        <textarea ref="promptInput" v-model="draft" rows="2" maxlength="16000" :disabled="busy || Boolean(pending)" :placeholder="mode === 'work' ? '描述你要完成的工作，或继续当前任务…' : '给 历史智能体对话发消息…'" aria-label="消息内容" data-testid="coding-prompt" @keydown="promptKeys"></textarea>
        <div class="coding-composer-toolbar"><div class="composer-controls">
          <template v-if="mode === 'work'"><span class="composer-chip environment-chip" title="文件操作限定所选项目；本机目录需要保持页面打开"><StudioIcon name="layout" :size="17" />{{ workspaceLabel }}</span><div class="project-control"><button type="button" class="composer-chip project-chip" :disabled="Boolean(session) || busy" :aria-expanded="projectOpen" @click="projectOpen ? closeProject() : projectOpen = true" data-testid="project-picker"><StudioIcon name="folder" :size="17" /><span>{{ selectedProject?.name || '选择项目' }}</span><span>⌄</span></button><div v-if="projectOpen" ref="projectPanel" role="dialog" aria-label="选择项目" class="composer-popover project-popover"><h2>选择项目</h2><button type="button" aria-label="关闭项目选择" @click="closeProject">关闭</button><button v-for="project in projects" :key="project.id" type="button" @click="chooseProject(project.id)"><StudioIcon name="folder" :size="17" />{{ project.name }}{{ project.source === 'local' ? ' · 本机' : '' }}<StudioIcon v-if="projectId === project.id" name="check" :size="16" /></button><p v-if="!projects.length" class="muted">创建项目后即可开始编码</p><div class="create-project"><input v-model="projectName" maxlength="80" placeholder="新项目名称" aria-label="新项目名称" @keydown="projectKeys" /><button type="button" :disabled="!projectName.trim() || projectBusy" @click="createProject" data-testid="create-project">创建</button></div><small>新建空白项目，文件保存在服务端。</small><button type="button" :disabled="projectBusy || !supportsLocal" @click="addLocalProject" data-testid="add-local-project"><StudioIcon name="folder" :size="17" />添加本机文件夹</button><small>直接读写电脑上的原目录；仅把任务读取的内容发送到服务端和模型。需要保持页面打开。</small><small v-if="!supportsLocal">当前浏览器不支持目录授权，请用桌面 Chrome/Edge 通过 HTTPS 打开。</small></div></div><div class="policy-control"><button type="button" class="composer-chip" :disabled="running || busy || Boolean(pending)" :aria-expanded="policyOpen" @click="policyOpen = !policyOpen" data-testid="approval-policy"><StudioIcon name="check" :size="17" />{{ policyLabel }}<span>⌄</span></button><div v-if="policyOpen" ref="policyPanel" role="dialog" aria-label="审批策略" class="composer-popover policy-popover"><label style="display:block;padding:12px">执行方式<select v-model="executionMode" :disabled="running || busy || Boolean(pending)" aria-label="执行方式" data-testid="execution-mode" style="display:block;width:100%;margin-top:8px"><option v-for="item in executionModes" :key="item.id" :value="item.id" :disabled="isLocal && item.id === 'team'">{{ item.label }}</option></select><small>服务端按权限和剩余预算执行；团队需可用执行器。审批规则独立设置。</small></label><button type="button" aria-label="关闭审批策略" @click="policyOpen = false">关闭</button><button v-for="item in policies" :key="item.id" type="button" :disabled="running || busy || Boolean(pending)" :class="{ selected: item.id === policy }" :aria-disabled="isLocal && item.id === 'allow'" @click="if (!(isLocal && item.id === 'allow')) { policy = item.id; policyOpen = false }" :data-testid="`policy-${item.id}`"><StudioIcon :name="item.id === 'ask' ? 'person' : item.id === 'allow' ? 'spark' : 'check'" :size="19" /><span><strong>{{ item.label }}</strong><small>{{ item.detail }}</small></span><StudioIcon v-if="item.id === policy" name="check" :size="18" /></button></div></div></template>
          <span v-else class="composer-chip"><StudioIcon name="spark" :size="17" />历史智能体对话</span>
          <div class="model-picker-control"><button type="button" class="composer-chip model-picker-trigger" :disabled="running || busy || Boolean(pending) || modelsLoading" :aria-expanded="modelPickerOpen" @click="modelPickerOpen = !modelPickerOpen" data-testid="model-picker"><StudioIcon name="spark" :size="16" /><span>{{ modelsLoading ? '加载模型…' : modelLabel }}</span><span>⌄</span></button><div v-if="modelPickerOpen" ref="modelPanel" role="dialog" aria-label="选择模型" class="composer-popover model-picker-popover"><button type="button" aria-label="关闭模型选择" @click="modelPickerOpen = false">关闭</button><p>为{{ mode === 'work' ? 'Coding Agent' : '历史对话' }}选择模型</p><button v-if="modelCatalog.systemEnabled !== false" type="button" :class="{ selected: effectiveModelId === 'system' }" @click="chooseModel('system')" data-testid="model-option-system"><span><strong>系统默认</strong><small>使用当前部署的模型配置</small></span><StudioIcon v-if="effectiveModelId === 'system'" name="check" :size="16" /></button><button v-for="profile in modelCatalog.profiles" :key="profile.id" type="button" :disabled="mode === 'work' && profile.codingSupported === false" :class="{ selected: effectiveModelId === profile.id }" @click="chooseModel(profile.id)" :data-testid="`model-option-${profile.id}`"><span><strong>{{ profile.name }}</strong><small>{{ profile.model }}</small><small v-if="mode === 'work' && profile.codingSupported === false">{{profile.codingUnavailableReason || '此模型未支持编程工具调用'}}</small></span><StudioIcon v-if="effectiveModelId === profile.id" name="check" :size="16" /></button><button type="button" class="model-manage-link" @click="openModelManager" data-testid="model-manage"><StudioIcon name="plus" :size="16" />前往总设置</button></div></div><ReasoningEffort :model-value="selectedEfforts[mode]" :levels="effortLevels" :disabled="running || busy || Boolean(pending) || modelsLoading" @update:model-value="chooseEffort"/><RouterLink to="/skills?tab=skills" class="composer-link"><StudioIcon name="layers" :size="17" />技能</RouterLink><RouterLink to="/mcp" class="composer-link"><StudioIcon name="plug" :size="17" />插件</RouterLink>
        </div><button v-if="running" type="button" class="coding-stop" :disabled="busy" aria-label="停止任务" @click="cancel" data-testid="coding-cancel"><span></span></button><button v-else type="submit" class="coding-send" :disabled="busy || modelsLoading || Boolean(modelsError) || (!pending && !selectedModelValid) || !draft.trim() || (mode === 'work' && (!workAvailable || !projectId))" :aria-label="pending ? '重试原消息' : '发送消息'" data-testid="coding-send"><StudioIcon name="arrow" :size="21" /></button></div>
      </form>
      <p class="composer-footnote">{{ mode === 'work' ? (capabilities?.shellEnabled ? '服务端项目文件 · 隔离命令执行已启用（无网络、只读项目快照）' : '当前支持项目文件读取与编辑 · 命令执行未启用或隔离环境不可用') : '当前对话由 历史智能体对话执行 · 历史按账号保存，可从历史列表恢复' }}</p>
    </footer>

  </main>
</template>
