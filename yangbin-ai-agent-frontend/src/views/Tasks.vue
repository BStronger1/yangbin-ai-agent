<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import WorkspaceLayout from '../components/WorkspaceLayout.vue'
import PlanningEvidence from '../components/PlanningEvidence.vue'
import { api, errorMessage } from '../workspace-api'
import { auth } from '../auth'

const route = useRoute(), router = useRouter()
const today = new Date().toLocaleDateString('en-CA')
const form = ref({ city: '北京', budget: 500, date: today, startTime: '14:00', hours: 6, interests: '咖啡馆、散步、轻松交流', transport: '公共交通', revision: '' })
const prompt = ref('按所选技能结合天气和预算，安排轻松的两人出行。')
const tasks = ref([]), task = ref(null), loading = ref(false), submitting = ref(false), action = ref('')
const error = ref(''), listError = ref(''), submissionError = ref(''), uncertain = ref(null), unavailable = ref(false)
const skills = ref([]), skillsError = ref(''), skillsLoading = ref(false), skillId = ref(typeof route.query.skill === 'string' ? route.query.skill : 'builtin-city')
const enabledSkills = computed(() => skills.value.filter(skill => skill.enabled))
const chosenSkill = computed(() => enabledSkills.value.find(skill => skill.id === skillId.value))
const stepTitles = {planning:'制定执行计划',requirements:'确认出行需求',knowledge:'检索知识库',evidence:'查询天气与地点',model:'生成出行方案',save:'校验并保存方案',pdf:'生成 PDF'}
const activeStatuses = ['QUEUED', 'RUNNING', 'CANCELLING']
const statusText = { AWAITING_CONFIRMATION: '等待确认', QUEUED: '等待执行', RUNNING: '正在执行', CANCELLING: '正在取消', SUCCEEDED: '已完成', FAILED: '执行失败', INTERRUPTED: '执行中断', CANCELLED: '已取消', PENDING: '等待执行' }
const selectedId = computed(() => typeof route.params.id === 'string' ? route.params.id : '')
const active = computed(() => activeStatuses.includes(task.value?.status))
const completed = computed(() => (task.value?.steps || []).filter(step => step.status === 'SUCCEEDED').length)
const events = computed(() => [...(task.value?.events || [])].sort((a, b) => b.sequence - a.sequence))
const totalCost = computed(() => (task.value?.itinerary?.stops || []).reduce((sum, stop) => sum + stop.estimatedCost, 0))
const owner = () => auth.user?.id || auth.user?.username || ''
const pendingKey = () => `yangbin-task-pending:${owner()}`
let epoch = 0, listEpoch = 0, disposed = false, timer, controller, listController, skillsController, skillsEpoch = 0

function label(status) { return statusText[status] || '状态待确认' }
function dateTime(value) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—' }
function duration(value) { return value == null ? '未提供' : value < 1000 ? `${value} 毫秒` : `${(value / 1000).toFixed(1)} 秒` }
function metric(value) { return value == null ? '未提供' : value }
function placeFor(stop) { return task.value?.evidence?.places?.find(place => place.id === stop.placeId) }
function safeUrl(value, sameOrigin = false) {
  if (!value) return ''
  try { const url = new URL(value, location.origin); return ['http:', 'https:'].includes(url.protocol) && (!sameOrigin || url.origin === location.origin) ? url.href : '' } catch { return '' }
}
const pdfUrl = computed(() => safeUrl(task.value?.pdfUrl, true))
function requestId() {
  if (globalThis.crypto?.randomUUID) return crypto.randomUUID()
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  bytes[6] = (bytes[6] & 15) | 64; bytes[8] = (bytes[8] & 63) | 128
  const hex = Array.from(bytes, value => value.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}
function persistPending(payload) {
  uncertain.value = payload
  try { if (payload) sessionStorage.setItem(pendingKey(), JSON.stringify(payload)); else sessionStorage.removeItem(pendingKey()) } catch { /* Current-page retries still retain the same request. */ }
}
function restorePending() {
  try {
    const saved = JSON.parse(sessionStorage.getItem(pendingKey()) || 'null')
    if (saved?.requestId && saved.requirements && typeof saved.prompt === 'string') {
      uncertain.value = saved; form.value = { ...saved.requirements }; prompt.value = saved.prompt; skillId.value = saved.skillId || 'builtin-city'
    }
  } catch { /* A corrupt draft must never affect server tasks. */ }
}
async function loadSkills() {
  const current = ++skillsEpoch, account = owner()
  skillsController?.abort(); skillsController = new AbortController(); skillsLoading.value = true
  try {
    const {data} = await api.get('/ai/skills', {signal: skillsController.signal, timeout: 20000})
    if (disposed || current !== skillsEpoch || account !== owner()) return
    skills.value = Array.isArray(data) ? data : []; skillsError.value = ''
  } catch (e) {
    if (!disposed && current === skillsEpoch && account === owner() && e.code !== 'ERR_CANCELED') { skills.value = []; skillsError.value = errorMessage(e) }
  } finally { if (!disposed && current === skillsEpoch) skillsLoading.value = false }
}
function clearTimer() { clearTimeout(timer); timer = undefined }
function schedule() {
  clearTimer()
  if (disposed || document.hidden || unavailable.value) return
  if (selectedId.value && task.value?.status === 'AWAITING_CONFIRMATION') return
  if (selectedId.value && !active.value && !error.value) return
  if (!selectedId.value && !tasks.value.some(item => activeStatuses.includes(item.status)) && !listError.value) return
  timer = setTimeout(() => selectedId.value ? loadDetail() : loadList(), 2000)
}
async function loadList() {
  const current = ++listEpoch, account = owner()
  listController?.abort(); listController = new AbortController()
  try {
    const { data } = await api.get('/ai/tasks', { signal: listController.signal, timeout: 20000 })
    if (disposed || current !== listEpoch || account !== owner()) return
    tasks.value = Array.isArray(data) ? data : []; listError.value = ''
  } catch (e) {
    if (disposed || current !== listEpoch || account !== owner() || e.code === 'ERR_CANCELED') return
    if (e.response?.status === 401 || e.response?.status === 403) tasks.value = []
    listError.value = errorMessage(e)
  } finally { if (!disposed && current === listEpoch && !selectedId.value) schedule() }
}
async function loadDetail(initial = false) {
  const id = selectedId.value, current = ++epoch, account = owner()
  if (!id || disposed || document.hidden) return
  controller?.abort(); controller = new AbortController()
  if (initial) loading.value = true
  try {
    const { data } = await api.get(`/ai/tasks/${encodeURIComponent(id)}`, { signal: controller.signal, timeout: 20000 })
    if (disposed || current !== epoch || id !== selectedId.value || account !== owner()) return
    if (data.id !== id) throw new Error('Task mismatch')
    task.value = data; error.value = ''; unavailable.value = false
    const index = tasks.value.findIndex(item => item.id === id)
    if (index >= 0) tasks.value[index] = data
    else tasks.value.unshift(data)
  } catch (e) {
    if (disposed || current !== epoch || id !== selectedId.value || account !== owner() || e.code === 'ERR_CANCELED') return
    if ([400, 401, 403, 404].includes(e.response?.status)) { task.value = null; unavailable.value = true }
    error.value = [400, 403, 404].includes(e.response?.status) ? '任务不存在或当前账号无权访问。' : errorMessage(e)
  } finally {
    if (!disposed && current === epoch) { loading.value = false; schedule() }
  }
}
async function submit() {
  if (submitting.value) return
  if (!uncertain.value && !chosenSkill.value) { submissionError.value = '请选择一个已启用的技能后再提交。'; return }
  submitting.value = true; submissionError.value = ''
  const account = owner(), startRoute = route.fullPath
  const payload = uncertain.value || { requestId: requestId(), prompt: prompt.value.trim(), skillId: skillId.value, requirements: { ...form.value } }
  persistPending(payload)
  try {
    const { data } = await api.post('/ai/tasks', payload, { timeout: 30000 })
    if (disposed || account !== owner()) return
    persistPending(null)
    await loadList()
    if (!disposed && startRoute === route.fullPath) await router.push(`/tasks/${encodeURIComponent(data.id)}`)
  } catch (e) {
    if (disposed || account !== owner()) return
    if ([400, 422].includes(e.response?.status)) persistPending(null)
    submissionError.value = uncertain.value ? '提交结果尚未确认。请重试同一任务，系统会复用本次请求，避免重复创建。' : errorMessage(e)
  } finally { if (!disposed && account === owner()) submitting.value = false }
}
async function mutate(kind) {
  if (!task.value || action.value) return
  const id = task.value.id, account = owner()
  action.value = kind; error.value = ''; clearTimer(); controller?.abort(); epoch++
  try {
    const { data } = await api.post(`/ai/tasks/${encodeURIComponent(id)}/${kind}`, undefined, { timeout: 30000 })
    if (disposed || id !== selectedId.value || account !== owner()) return
    if (data?.id === id) task.value = data
    await loadDetail(); await loadList()
  } catch (e) {
    if (!disposed && id === selectedId.value && account === owner()) error.value = '操作结果尚未确认，请刷新任务状态后再操作。' + errorMessage(e)
  } finally { if (!disposed && account === owner()) { action.value = ''; schedule() } }
}
function visibilityChanged() {
  clearTimer()
  if (document.hidden) { controller?.abort(); listController?.abort(); skillsController?.abort(); epoch++; listEpoch++; skillsEpoch++; return }
  loadList(); if (selectedId.value) loadDetail(true); else loadSkills()
}
watch(selectedId, () => {
  clearTimer(); controller?.abort(); epoch++; task.value = null; error.value = ''; loading.value = false; unavailable.value = false
  if (selectedId.value) loadDetail(true)
  else { loadList(); loadSkills() }
})
watch(() => route.query.skill, value => {
  if (!selectedId.value && !uncertain.value) { skillId.value = typeof value === 'string' ? value : 'builtin-city'; loadSkills() }
})
watch(() => owner(), () => {
  epoch++; listEpoch++; skillsEpoch++; controller?.abort(); listController?.abort(); skillsController?.abort(); clearTimer()
  task.value = null; tasks.value = []; uncertain.value = null; error.value = ''; listError.value = ''; submissionError.value = ''; unavailable.value = false
  skills.value = []; skillsError.value = ''; skillsLoading.value = false; submitting.value = false; action.value = ''; loading.value = false; skillId.value = 'builtin-city'
  prompt.value = ''; form.value = { city: '', budget: 500, date: today, startTime: '14:00', hours: 6, interests: '', transport: '公共交通', revision: '' }
  if (owner()) { restorePending(); loadList(); if (selectedId.value) loadDetail(true); else loadSkills() }
})
onMounted(() => { restorePending(); document.addEventListener('visibilitychange', visibilityChanged); loadList(); if (selectedId.value) loadDetail(true); else loadSkills() })
onBeforeUnmount(() => { disposed = true; epoch++; listEpoch++; skillsEpoch++; clearTimer(); controller?.abort(); listController?.abort(); skillsController?.abort(); document.removeEventListener('visibilitychange', visibilityChanged) })
</script>

<template>
  <WorkspaceLayout title="任务工作台" description="选择技能，先确认执行计划，再逐步获取带来源的出行方案。进度保存后可继续执行。">
    <div class="task-toolbar row"><span class="badge">第二版 · 技能与计划</span><RouterLink v-if="selectedId" class="button" to="/tasks">新建任务</RouterLink><button class="secondary" :disabled="loading || !!action" @click="loadList(); selectedId && loadDetail(true)">刷新任务状态</button></div>
    <div class="task-grid">
      <aside class="panel task-list" aria-label="任务列表">
        <div class="row list-heading"><h2>我的任务</h2><span class="muted">最近 {{tasks.length}} 项</span></div>
        <p v-if="listError" class="error" role="alert">{{listError}}</p>
        <RouterLink v-for="item in tasks" :key="item.id" :to="`/tasks/${item.id}`" class="task-list-item" :class="{selected: item.id === selectedId}" :aria-current="item.id === selectedId ? 'page' : undefined">
          <strong>{{item.title || '出行任务'}}</strong><div class="row"><span class="task-status" :class="item.status">{{label(item.status)}}</span><time>{{dateTime(item.createdAt)}}</time></div>
        </RouterLink>
        <p v-if="!tasks.length && !listError" class="muted empty-list">提交第一个任务后，可在这里查看进度和结果。</p>
      </aside>
      <div class="task-main">
        <section v-if="!selectedId" class="panel">
          <h2>新建出行任务</h2><p class="form-intro">填写需求并选择技能。系统先制定执行计划，展示每步目的；确认后再查询资料、生成方案并按计划交付结果。</p>
          <div v-if="uncertain" class="notice" role="status">有一条提交结果待确认的任务。需求暂时锁定，点击“重试同一任务”确认结果，不会重复创建。</div>
          <div v-if="submissionError" class="error" role="alert">{{submissionError}}</div>
          <div v-if="skillsError" class="error" role="alert">{{skillsError}} <button class="secondary" type="button" @click="loadSkills">重新加载技能</button></div>
          <form @submit.prevent="submit">
            <fieldset :disabled="submitting || !!uncertain">
              <label>选择技能<select v-model="skillId" :disabled="skillsLoading" required><option v-if="!chosenSkill" :value="skillId" disabled>{{skillsLoading ? '正在加载技能…' : uncertain ? '本次提交保存的技能' : '所选技能不可用，请重新选择'}}</option><option v-for="skill in enabledSkills" :key="skill.id" :value="skill.id">{{skill.name}} · v{{skill.version}}</option></select></label>
              <div v-if="chosenSkill" class="notice skill-selection"><p>{{chosenSkill.description}}</p><p class="muted">天气与地点必需<span v-if="chosenSkill.useKnowledge"> · 允许知识检索</span><span v-if="chosenSkill.exportPdf"> · 允许 PDF 导出</span></p></div>
              <p class="muted form-intro"><RouterLink to="/skills">管理或创建我的技能 →</RouterLink></p>
              <label>任务描述<textarea v-model="prompt" required maxlength="2000" placeholder="例如：安排北京周六两人出行，预算 500 元，考虑天气，优先室内活动。"></textarea></label>
              <div class="split"><label>中国城市<input v-model="form.city" required maxlength="80"></label><label>两人总预算（元）<input v-model.number="form.budget" type="number" min="1" max="100000" required></label></div>
              <div class="split"><label>日期<input v-model="form.date" type="date" required></label><label>开始时间<input v-model="form.startTime" type="time" required></label></div>
              <div class="split"><label>时长（小时）<input v-model.number="form.hours" type="number" min="1" max="12" required></label><label>交通方式<select v-model="form.transport"><option>公共交通</option><option>步行</option><option>自驾</option><option>打车</option></select></label></div>
              <label>兴趣与偏好<textarea v-model="form.interests" maxlength="1000" placeholder="例如：安静的咖啡馆、散步，雨天优先室内"></textarea></label>
            </fieldset>
            <button type="submit" :disabled="submitting || (!uncertain && (skillsLoading || !chosenSkill))">{{submitting ? '正在提交…' : uncertain ? '重试同一任务' : '提交任务'}}</button>
            <p class="muted form-intro">制定计划与生成方案会调用已配置的模型。天气、地图查询可能暂时不可用；活动价格、营业状态和交通耗时需要出行前确认。</p>
          </form>
        </section>
        <template v-else>
          <p v-if="error" class="error" role="alert">{{error}}</p>
          <section v-if="loading && !task" class="panel" role="status">正在加载任务…</section>
          <template v-if="task">
            <section class="panel task-overview">
              <div class="row overview-top"><span class="task-status" :class="task.status" data-testid="task-status">{{label(task.status)}}</span><span class="muted">已完成 {{completed}} / {{task.steps?.length || 6}} 步</span></div>
              <h2>{{task.title || '出行任务'}}</h2><p class="task-prompt">{{task.prompt}}</p>
              <p class="muted">{{task.requirements?.city}} · {{task.requirements?.date}} {{task.requirements?.startTime}} · {{task.requirements?.hours}} 小时 · 两人预算 ¥{{task.requirements?.budget}}</p>
              <p class="muted">创建于 {{dateTime(task.createdAt)}} · 更新于 {{dateTime(task.updatedAt)}}</p>
              <div class="progress-track" role="progressbar" :aria-valuenow="completed" aria-valuemin="0" :aria-valuemax="task.steps?.length || 6" aria-label="任务步骤进度"><div :style="{width: `${completed / (task.steps?.length || 6) * 100}%`}"></div></div>
              <p v-if="task.status === 'AWAITING_CONFIRMATION'" class="notice" role="status">执行计划已准备好，请查看每一步的目的。确认后才会开始查询和生成方案。</p>
              <p v-if="task.status === 'CANCELLING'" class="notice" role="status">取消请求已记录，当前步骤结束后停止，不再启动后续步骤。</p>
              <p v-if="task.status === 'INTERRUPTED'" class="notice">任务执行中断，已完成步骤已保存。点击“继续执行”从保存的进度恢复。</p>
              <p v-if="task.status === 'FAILED'" class="error">{{task.error || '任务未完成，请查看失败步骤后重试。'}}</p>
              <p v-if="task.status === 'CANCELLED'" class="notice">任务已取消，已完成的执行记录仍然保留。</p>
              <div class="row task-actions">
                <button v-if="['QUEUED','RUNNING','AWAITING_CONFIRMATION'].includes(task.status)" class="danger" :disabled="!!action" @click="mutate('cancel')">{{action === 'cancel' ? '正在请求取消…' : '取消任务'}}</button>
                <button v-if="['FAILED','INTERRUPTED'].includes(task.status)" :disabled="!!action" @click="mutate('retry')">{{action === 'retry' ? '正在恢复…' : task.status === 'INTERRUPTED' ? '继续执行' : '重试失败步骤'}}</button>
                <a v-if="pdfUrl" class="button" :href="pdfUrl" target="_blank" rel="noopener noreferrer">下载 PDF</a>
              </div>
              <p v-if="['QUEUED','RUNNING'].includes(task.status)" class="muted">进度自动更新。离开或刷新页面不会取消任务；取消将在当前步骤结束后生效。</p>
            </section>
            <section v-if="task.skill" class="panel" aria-label="任务技能快照"><div class="row"><h2>本次使用的技能</h2><span class="badge">版本 {{task.skill.version}}</span></div><h3>{{task.skill.name}}</h3><p>{{task.skill.description}}</p><p class="muted">这是创建任务时保存的技能版本。后续编辑、停用或删除技能，不会改变本任务。</p><details><summary>查看执行指引</summary><p class="task-prompt">{{task.skill.instructions}}</p></details></section>
            <section v-if="task.executionPlan" class="panel execution-plan" aria-label="执行计划" data-testid="execution-plan"><div class="row"><h2>执行计划</h2><span class="badge">{{task.confirmedAt ? '已确认' : task.status === 'AWAITING_CONFIRMATION' ? '等待你的确认' : '已制定'}}</span></div><p>{{task.executionPlan.summary}}</p><ol class="plan-steps"><li v-for="step in task.executionPlan.steps" :key="step.id" :data-testid="`planned-step-${step.id}`"><h3>{{stepTitles[step.id] || '执行步骤'}}</h3><p>{{step.reason}}</p></li></ol><p v-if="task.confirmedAt" class="muted">确认于 {{dateTime(task.confirmedAt)}}</p><div v-if="task.status === 'AWAITING_CONFIRMATION'" class="row"><button :disabled="!!action" @click="mutate('start')">{{action === 'start' ? '正在开始…' : '确认并执行'}}</button><p class="muted">确认后按上述步骤执行，可随时请求取消。</p></div></section>
            <section class="panel" aria-label="执行时间线">
              <h2>执行时间线</h2>
              <ol class="task-timeline">
                <li v-for="(step,index) in task.steps" :key="step.id" :class="step.status" :data-testid="`task-step-${step.id}`">
                  <span class="step-number">{{step.status === 'SUCCEEDED' ? '✓' : index + 1}}</span>
                  <div class="step-body"><div class="row"><h3>{{step.title || stepTitles[step.id] || '执行步骤'}}</h3><span class="task-status" :class="step.status">{{label(step.status)}}</span></div><p v-if="step.summary">{{step.summary}}</p><p v-if="step.error" class="step-error">{{step.error}}</p><p class="muted">尝试 {{step.attempts || 0}} 次<span v-if="step.startedAt"> · {{dateTime(step.startedAt)}}</span><span v-if="step.durationMs != null"> · 耗时 {{duration(step.durationMs)}}</span></p></div>
                </li>
              </ol>
            </section>
            <section v-if="task.itinerary" class="panel" aria-label="任务方案">
              <div class="row"><span class="badge">{{task.planId ? '已保存方案' : '已生成草案'}}</span><span v-if="task.planVersion" class="muted">版本 {{task.planVersion}}</span></div>
              <h2 class="result-title">{{task.itinerary.title}}</h2><p>{{task.itinerary.summary}}</p><p class="cost">估算总费用 <strong>¥{{totalCost}}</strong><span class="muted"> / 两人预算 ¥{{task.requirements?.budget}}</span></p>
              <div class="timeline"><article v-for="(stop,index) in task.itinerary.stops" :key="index" class="stop"><span class="badge">{{stop.time}}</span><h3>{{stop.activity}}</h3><p>{{stop.note}}</p><p v-if="placeFor(stop)">候选地点：<a :href="safeUrl(placeFor(stop).url)" target="_blank" rel="noopener noreferrer">{{placeFor(stop).name}}</a></p><p class="muted">估算费用 ¥{{stop.estimatedCost}}</p></article></div>
              <h3>备选方案</h3><ul><li v-for="(text,index) in task.itinerary.alternatives" :key="index">{{text}}</li></ul>
              <h3>出行前确认</h3><ul><li v-for="(text,index) in task.itinerary.cautions" :key="index">{{text}}</li></ul>
            </section>
            <PlanningEvidence v-if="task.evidence" :evidence="task.evidence" snapshot snapshot-description="这是本任务查询时保存的依据，后续执行复用这份记录。需要查询最新情况，可前往“约会规划”页面。" />
            <section v-if="task.sources?.length" class="panel"><details><summary>参考知识库 · {{task.sources.length}} 条</summary><article v-for="(source,index) in task.sources" :key="index" class="result"><h3>{{source.source}}</h3><p>{{source.content}}</p></article></details></section>
            <section class="panel"><h2>执行记录</h2><div class="task-metrics"><div><span>累计执行耗时</span><strong>{{duration(task.metrics?.durationMs)}}</strong></div><div><span>模型调用</span><strong>{{metric(task.metrics?.modelCalls)}}</strong></div><div><span>工具调用</span><strong>{{metric(task.metrics?.toolCalls)}}</strong></div><div><span>失败尝试</span><strong>{{metric(task.metrics?.failedAttempts)}}</strong></div><div><span>输入 Token</span><strong>{{metric(task.metrics?.promptTokens)}}</strong></div><div><span>输出 Token</span><strong>{{metric(task.metrics?.completionTokens)}}</strong></div></div><p class="muted">未提供的用量不计为零，实际费用以模型服务账单为准。</p><details class="events" open><summary>事件日志 · {{events.length}} 条</summary><ol><li v-for="event in events" :key="event.sequence"><time>{{dateTime(event.time)}}</time><span>{{event.message}}</span></li></ol><p v-if="!events.length" class="muted">暂时没有执行事件。</p></details></section>
          </template>
        </template>
      </div>
    </div>
  </WorkspaceLayout>
</template>

<style scoped>
.execution-plan h2{margin:0}.execution-plan>.row{margin-bottom:16px}.plan-steps{padding-left:22px;margin:20px 0}.plan-steps li{padding:0 0 10px 5px}.plan-steps p{font-size:14px}.skill-selection p{font-size:13px}.task-toolbar{margin-bottom:22px}.task-grid{display:grid;grid-template-columns:minmax(230px,.72fr) minmax(0,2fr);gap:22px;align-items:start}.task-main{min-width:0;overflow-wrap:anywhere}.list-heading{justify-content:space-between}.list-heading h2{margin:0}.task-list-item{display:block;padding:16px 12px;border-radius:10px;margin-top:10px;border:1px solid #e9eef1;overflow-wrap:anywhere}.task-list-item:hover,.task-list-item.selected{background:#edf7f3;border-color:#a5d1c6}.task-list-item strong{display:block;font-size:14px;line-height:1.5;margin-bottom:10px}.task-list-item time{font-size:11px;color:#72828b}.task-list-item .row{gap:6px}.empty-list{margin-top:22px}.task-status{display:inline-block;font-size:12px;font-weight:600;color:#617681;background:#edf1f4;padding:4px 8px;border-radius:6px;white-space:nowrap}.task-status.SUCCEEDED{background:#e5f5ed;color:#247454}.task-status.RUNNING,.task-status.QUEUED{background:#e4f2fa;color:#266885}.task-status.FAILED{background:#ffefeb;color:#b04233}.task-status.INTERRUPTED,.task-status.CANCELLING,.task-status.AWAITING_CONFIRMATION{background:#fff3d9;color:#866319}.form-intro{margin:12px 0 20px;font-size:14px}fieldset{border:0;min-width:0}.task-overview h2{font-size:25px;margin:18px 0 12px}.task-prompt{white-space:pre-wrap;margin-bottom:12px}.overview-top{justify-content:space-between}.progress-track{height:6px;border-radius:5px;background:#e8efec;margin:22px 0;overflow:hidden}.progress-track>div{height:100%;background:#3f9c89;transition:width .3s}.task-actions{margin:16px 0}.task-timeline{padding:0;list-style:none}.task-timeline>li{display:flex;gap:14px;position:relative;padding-bottom:24px;min-width:0}.task-timeline>li:last-child{padding-bottom:0}.task-timeline>li:not(:last-child):before{content:'';position:absolute;left:15px;top:34px;bottom:4px;width:2px;background:#e3ebe7}.step-number{display:flex;flex:none;align-items:center;justify-content:center;width:32px;height:32px;border:1px solid #d9e5df;background:#f6f9f7;color:#73817b;border-radius:50%;font-size:13px}.SUCCEEDED>.step-number{background:#e3f4ec;color:#1b7554;border-color:#9fceba}.RUNNING>.step-number{background:#e6f3f9;color:#236f8e;border-color:#8ebecf}.FAILED>.step-number{background:#fff0eb;color:#b44936;border-color:#edb9a7}.step-body{min-width:0;flex:1}.step-body h3{margin:5px 0;font-size:15px}.step-body p{font-size:13px;margin-top:6px}.step-body .step-error{color:#ac3d2b}.task-metrics{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:14px;margin-bottom:18px}.task-metrics>div{background:#f6f9f8;border:1px solid #e7eeeb;padding:14px;border-radius:9px}.task-metrics span{display:block;font-size:12px;color:#70857c;margin-bottom:8px}.task-metrics strong{font-size:19px;color:#276b57}.events ol{list-style:none;padding:0;max-height:340px;overflow-y:auto;margin-top:16px}.events li{display:flex;flex-direction:column;gap:5px;padding:12px 0;border-bottom:1px solid #edf1ee;font-size:13px;line-height:1.6}.events time{color:#839189;font-size:11px}.cost{margin:20px 0}.cost strong{font-size:26px;color:#196f69}.result-title{margin-top:20px}.task-main a:not(.button){color:#196f69}.task-list{max-height:75vh;overflow-y:auto}@media(max-width:850px){.task-grid{grid-template-columns:1fr}.task-list{max-height:260px}.task-list-item{padding:12px}.task-list-item strong{margin-bottom:7px}.task-metrics{grid-template-columns:repeat(2,minmax(0,1fr))}.execution-plan h2{margin:0}.execution-plan>.row{margin-bottom:16px}.plan-steps{padding-left:22px;margin:20px 0}.plan-steps li{padding:0 0 10px 5px}.plan-steps p{font-size:14px}.skill-selection p{font-size:13px}.task-toolbar{gap:8px}.task-overview h2{font-size:22px}.task-timeline>li{gap:10px}}
</style>
