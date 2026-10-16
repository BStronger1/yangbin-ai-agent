<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { auth } from '../auth'
import { api, errorMessage } from '../workspace-api'
import PlanQuality from './PlanQuality.vue'

const props = defineProps({ planId: String, version: Number, quality: Object })
const emit = defineEmits(['repaired'])
const view = ref(null), loading = ref(false), posting = ref(false), error = ref(''), outcomeUnknown = ref(false)
const pdfBusy = ref(false), pdfUrl = ref(''), pdfError = ref('')
const activeStatuses = ['RUNNING', 'PUBLISHING']
const labels = { NOT_STARTED: '尚未纠错', RUNNING: '正在尝试纠错', PUBLISHING: '正在保存新版本', REPAIRED: '已保存纠错新版本', REJECTED: '未采用纠错结果', FAILED: '纠错未完成', INTERRUPTED: '纠错已中断', CONFLICT: '方案版本已变化' }
const warningCount = quality => Array.isArray(quality?.findings) ? quality.findings.filter(item => item?.severity === 'warning').length : 0
const warnings = computed(() => warningCount(props.quality))
const active = computed(() => activeStatuses.includes(view.value?.status))
const canStart = computed(() => view.value?.status === 'NOT_STARTED' && !view.value?.attempts && warnings.value > 0 && !loading.value && !posting.value && !error.value && !outcomeUnknown.value && Number.isInteger(props.version) && props.version > 0)
const owner = () => auth.user?.id || auth.user?.username || ''
const identity = () => `${owner()}:${props.planId || ''}:${props.version || ''}`
let epoch = 0, controller, timer, mounted = false, disposed = false, startedHere = false

function safeDownload(value) {
  if (!value || typeof location === 'undefined') return ''
  try { const url = new URL(value, location.origin); return ['http:', 'https:'].includes(url.protocol) && url.origin === location.origin && !url.username && !url.password ? url.href : '' } catch { return '' }
}
function stopTimer() { clearTimeout(timer); timer = undefined }
function schedule() {
  stopTimer()
  if (!mounted || disposed || document.hidden || (!active.value && !outcomeUnknown.value)) return
  timer = setTimeout(refresh, 2000)
}
function accept(data) {
  if (!data || data.planId !== props.planId) throw new Error('Repair response mismatch')
  view.value = data; outcomeUnknown.value = false; error.value = ''
  if (startedHere && data.status === 'REPAIRED' && Number.isInteger(data.targetVersion)) {
    startedHere = false
    emit('repaired', { planId: data.planId, version: data.targetVersion })
  }
}
async function refresh() {
  if (!props.planId || disposed || !mounted || document.hidden) return
  const current = ++epoch, context = identity()
  controller?.abort(); controller = new AbortController(); loading.value = true
  try {
    const { data } = await api.get(`/ai/plans/${encodeURIComponent(props.planId)}/repair`, { signal: controller.signal, timeout: 20000 })
    if (disposed || current !== epoch || context !== identity()) return
    accept(data)
  } catch (e) {
    if (!disposed && current === epoch && context === identity() && e.code !== 'ERR_CANCELED') error.value = errorMessage(e)
  } finally {
    if (!disposed && current === epoch && context === identity()) { loading.value = false; schedule() }
  }
}
async function start() {
  if (!canStart.value) return
  const context = identity(), id = props.planId, version = props.version
  posting.value = true; error.value = ''; startedHere = true; stopTimer(); controller?.abort(); const mutation = ++epoch
  try {
    const { data } = await api.post(`/ai/plans/${encodeURIComponent(id)}/repair`, { expectedVersion: version }, { timeout: 30000 })
    if (disposed || context !== identity()) return
    if (epoch === mutation) accept(data); else await refresh()
  } catch (e) {
    if (disposed || context !== identity()) return
    outcomeUnknown.value = true
    error.value = '请求结果尚未确认，正在查询状态；不会自动再次提交纠错。'
    // Only read status after an uncertain POST. Never automatically retry a model call.
    await refresh()
  } finally {
    if (!disposed && context === identity()) { posting.value = false; schedule() }
  }
}
async function exportPdf() {
  if (pdfBusy.value || view.value?.status !== 'REPAIRED' || !Number.isInteger(view.value.targetVersion)) return
  const context = identity(), id = props.planId, version = view.value.targetVersion
  pdfBusy.value = true; pdfError.value = ''; pdfUrl.value = ''
  try {
    const { data } = await api.post(`/ai/plans/${encodeURIComponent(id)}/${version}/pdf`, undefined, { timeout: 60000 })
    if (disposed || context !== identity()) return
    pdfUrl.value = safeDownload(data.url)
    if (!pdfUrl.value) pdfError.value = '未取得可用的下载地址，请稍后重试。'
  } catch (e) { if (!disposed && context === identity()) pdfError.value = errorMessage(e) }
  finally { if (!disposed && context === identity()) pdfBusy.value = false }
}
function reset() {
  epoch++; controller?.abort(); stopTimer(); view.value = null; loading.value = false; posting.value = false; error.value = ''; outcomeUnknown.value = false; pdfBusy.value = false; pdfUrl.value = ''; pdfError.value = ''; startedHere = false
  if (mounted && owner()) refresh()
}
function visibilityChanged() {
  if (document.hidden) { epoch++; controller?.abort(); loading.value = false; stopTimer() }
  else refresh()
}
watch(() => [props.planId, props.version, owner()], reset)
onMounted(() => { mounted = true; document.addEventListener('visibilitychange', visibilityChanged); reset() })
onBeforeUnmount(() => { disposed = true; mounted = false; epoch++; controller?.abort(); stopTimer(); document.removeEventListener('visibilitychange', visibilityChanged) })
</script>

<template>
  <section v-if="planId" class="panel plan-repair" aria-label="按报告纠错" data-testid="plan-repair">
    <div class="repair-heading"><h2>按检查报告纠错</h2><span class="repair-state" :class="view?.status" data-testid="repair-status">{{view ? labels[view.status] || '状态待确认' : loading ? '正在读取状态…' : '状态待查询'}}</span></div>
    <p class="repair-intro">每个方案最多尝试一次，会额外调用模型并产生费用。仅修订文字，保留站点数量、顺序、时间、费用和地点引用。使用原始依据快照，不查询新来源；原方案和原 PDF 保留，成功后另存新版本。</p>
    <p v-if="error" class="error" role="alert">{{error}}</p>
    <div v-if="view?.status === 'NOT_STARTED'" class="repair-start">
      <p v-if="!quality" class="muted">历史方案暂无来源检查报告，暂不能从这里发起纠错。缺少报告不代表方案没有问题。</p>
      <p v-else-if="!warnings" class="muted">当前报告没有警告项，暂不提供模型纠错；报告中的未核实信息仍需人工确认。</p>
      <p v-else class="muted">当前报告包含 {{warnings}} 项警告。模型将尝试修正这些问题，不保证全部消除，也不代表所有事实都已核实。</p>
      <button type="button" :disabled="!canStart" @click="start">{{posting ? '正在提交…' : '按检查报告纠错一次'}}</button>
    </div>
    <p v-if="active || posting" class="notice" role="status">正在处理本次纠错。刷新或离开页面不会重复提交；处理结果会保存，稍后可以回来查看。</p>
    <p v-if="view?.message" class="repair-message">{{view.message}}</p>
    <div v-if="view && !['NOT_STARTED','RUNNING','PUBLISHING'].includes(view.status)" class="repair-result">
      <p class="muted">本次纠错已结束，此处不再重复提交。保留原文，结果仍需结合来源与实际情况判断。</p>
      <p class="muted">以下前后报告使用更新后的检查规则，基于同一份原始依据快照重新检查；原版本中既有的历史报告保持不变。</p>
      <div class="repair-comparison-counts"><div><span>原方案警告</span><strong>{{view.beforeQuality ? warningCount(view.beforeQuality) : '未检查'}}</strong></div><div><span>纠错结果警告</span><strong>{{view.afterQuality ? warningCount(view.afterQuality) : '暂无报告'}}</strong></div></div>
      <div class="repair-comparison">
        <details v-if="view.beforeItinerary || view.beforeQuality" class="repair-version" data-testid="repair-before"><summary>查看原方案 · 版本 {{view.sourceVersion}}</summary><div v-if="view.beforeItinerary"><h3>{{view.beforeItinerary.title}}</h3><p>{{view.beforeItinerary.summary}}</p><ol class="repair-stops"><li v-for="(stop,index) in view.beforeItinerary.stops || []" :key="index"><strong>{{stop.time}} · {{stop.activity}}</strong><p>{{stop.note}}</p><p class="muted">估算费用 ¥{{stop.estimatedCost}}</p></li></ol><h3 v-if="view.beforeItinerary.alternatives?.length">备选方案</h3><ul><li v-for="(text,index) in view.beforeItinerary.alternatives || []" :key="index">{{text}}</li></ul><h3 v-if="view.beforeItinerary.cautions?.length">原方案提醒</h3><ul><li v-for="(text,index) in view.beforeItinerary.cautions || []" :key="index">{{text}}</li></ul></div><PlanQuality :quality="view.beforeQuality" /></details>
        <details v-if="view.afterItinerary || view.afterQuality" class="repair-version" data-testid="repair-after"><summary>{{view.status === 'REPAIRED' ? '查看纠错新版本' : '查看未采用的纠错结果'}}<span v-if="view.targetVersion"> · 版本 {{view.targetVersion}}</span></summary><div v-if="view.afterItinerary"><h3>{{view.afterItinerary.title}}</h3><p>{{view.afterItinerary.summary}}</p><ol class="repair-stops"><li v-for="(stop,index) in view.afterItinerary.stops || []" :key="index"><strong>{{stop.time}} · {{stop.activity}}</strong><p>{{stop.note}}</p><p class="muted">估算费用 ¥{{stop.estimatedCost}}</p></li></ol><h3 v-if="view.afterItinerary.alternatives?.length">备选方案</h3><ul><li v-for="(text,index) in view.afterItinerary.alternatives || []" :key="index">{{text}}</li></ul><h3 v-if="view.afterItinerary.cautions?.length">纠错结果提醒</h3><ul><li v-for="(text,index) in view.afterItinerary.cautions || []" :key="index">{{text}}</li></ul></div><PlanQuality :quality="view.afterQuality" /></details>
      </div>
      <div v-if="view.status === 'REPAIRED' && view.targetVersion" class="row repair-actions"><RouterLink class="button" :to="{path:'/planner',query:{plan:planId,version:String(view.targetVersion)}}">打开纠错新版本</RouterLink><button type="button" :disabled="pdfBusy" @click="exportPdf">{{pdfBusy ? '正在导出…' : '导出纠错版本 PDF'}}</button><a v-if="pdfUrl" class="button" :href="pdfUrl" target="_blank" rel="noopener noreferrer">下载纠错版本 PDF</a></div>
      <p v-if="pdfError" class="error" role="alert">{{pdfError}}</p>
    </div>
    <button v-if="error || outcomeUnknown || active" type="button" class="secondary repair-refresh" :disabled="loading || posting" @click="refresh">查询纠错状态</button>
  </section>
</template>

<style scoped>
.plan-repair{min-width:0;overflow-wrap:anywhere}.repair-heading{display:flex;justify-content:space-between;align-items:flex-start;gap:12px;flex-wrap:wrap;margin-bottom:14px}.repair-heading h2{margin:0}.repair-state{font-size:12px;line-height:1.6;padding:5px 9px;border-radius:6px;background:#eef3f4;color:#617682}.repair-state.REPAIRED{background:#eaf5ed;color:#327353}.repair-state.RUNNING,.repair-state.PUBLISHING{background:#eaf4fa;color:#316e8b}.repair-state.REJECTED,.repair-state.FAILED,.repair-state.CONFLICT,.repair-state.INTERRUPTED{background:#fff3df;color:#88631e}.repair-intro,.repair-message{font-size:13px;margin:10px 0 16px}.repair-start button{margin-top:14px}.repair-start{margin-bottom:16px}.repair-comparison-counts{display:grid;grid-template-columns:1fr 1fr;gap:12px;margin:18px 0}.repair-comparison-counts>div{padding:14px;background:#f5f8f6;border-radius:9px}.repair-comparison-counts span{display:block;font-size:12px;color:#6f8278;margin-bottom:8px}.repair-comparison-counts strong{font-size:22px;color:#3a6a56}.repair-comparison{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1fr);gap:16px}.repair-version{min-width:0;border:1px solid #dfe8e3;border-radius:9px;padding:14px;margin:0!important}.repair-version summary{line-height:1.7}.repair-version p,.repair-version li{font-size:13px}.repair-version .panel{padding:14px;margin:18px 0 0;box-shadow:none}.repair-stops{padding-left:18px;margin:15px 0}.repair-stops li{margin-bottom:16px}.repair-stops p{margin-top:5px}.repair-actions{margin-top:20px}.repair-refresh{margin-top:16px}@media(max-width:900px){.repair-comparison{grid-template-columns:1fr}.repair-heading{flex-direction:column;gap:8px}.repair-comparison-counts>div{padding:12px}.repair-actions{gap:8px}.repair-actions .button,.repair-actions button{font-size:13px;padding:9px 12px}}
</style>
