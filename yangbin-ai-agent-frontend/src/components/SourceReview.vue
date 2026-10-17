<script setup>
import { ref, computed, watch, onBeforeUnmount } from 'vue'
import { api, errorMessage } from '../workspace-api'
import { auth } from '../auth'
const props = defineProps({ planId: String, version: Number })
const report = ref(null), busy = ref(false), error = ref('')
const owner = () => auth.user?.id || auth.user?.username || ''
let epoch = 0, controller, alive = true
const claims = computed(() => Array.isArray(report.value?.claims) ? report.value.claims : [])
const alerts = computed(() => claims.value.filter(c => ['UNSUPPORTED', 'CONTRADICTED'].includes(c.verdict)))
const labels = { SUPPORTED: '与保存依据对应', UNSUPPORTED: '缺少支持依据', CONTRADICTED: '与快照不一致', NOT_ASSESSABLE: '当前无法判断', NOT_ASSERTION: '条件或未确认说明' }
const categories = { WEATHER_PRECISION: '天气精度', DAILY_FORECAST: '日预报', RAIN_PROBABILITY: '降水概率', WEATHER_OTHER: '其他天气表达', CURRENT_PRICE: '实际价格', OPENING_OR_BOOKING: '营业与预约', ROUTE_TIME_OR_DISTANCE: '路线耗时', SPECIFIC_EXHIBITION: '具体展览', PLACE_REFERENCE: '地点引用', UNCERTAINTY_OR_CONDITION: '不确定性说明', ESTIMATE_OR_POSSIBILITY: '估算或可能性', UNCLASSIFIED: '未覆盖文本' }
function reset() { epoch++; controller?.abort(); report.value = null; error.value = ''; busy.value = false }
watch(() => [props.planId, props.version, owner()], reset)
async function inspect() {
  if (!props.planId || !props.version || busy.value || !owner()) return
  const current = ++epoch, account = owner(), id = props.planId, version = props.version
  controller?.abort(); controller = new AbortController(); busy.value = true; error.value = ''
  try {
    const { data } = await api.get('/ai/plans/' + encodeURIComponent(id) + '/' + version + '/source-review', { signal: controller.signal, timeout: 20000 })
    if (!alive || epoch !== current || account !== owner()) return
    if (data.planId !== id || data.planVersion !== version || !Array.isArray(data.review?.claims)) throw new Error('Invalid review binding')
    report.value = data.review
  } catch (e) {
    if (alive && epoch === current && account === owner() && e.code !== 'ERR_CANCELED') error.value = errorMessage(e)
  } finally { if (alive && epoch === current && account === owner()) busy.value = false }
}
onBeforeUnmount(() => { alive = false; reset() })
</script>
<template>
  <section class="panel source-review" aria-label="逐条来源核验" data-testid="source-review">
    <div class="review-heading"><h2>逐条来源核验</h2><button class="secondary" :disabled="busy || !planId" @click="inspect">{{ busy ? '正在检查…' : report ? '重新检查此版本' : '检查此版本' }}</button></div>
    <p class="muted">结合此版本保存的天气与地点资料，检查具体断言。来源不会自动更新；当前仅覆盖部分中文出行表达。</p>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <template v-if="report">
      <p class="review-summary" role="status">{{ alerts.length ? '发现 ' + alerts.length + ' 条需要核实的断言。' : '在已覆盖的表达中未发现缺依据断言；这不表示全文正确。' }}</p>
      <article v-for="(claim, index) in alerts" :key="index" class="claim alert-claim">
        <strong>{{ categories[claim.category] || '待复核断言' }} · {{ labels[claim.verdict] }}</strong>
        <blockquote>{{ claim.text }}</blockquote><p>{{ claim.reason }}</p>
      </article>
      <details>
        <summary>查看全部 {{ claims.length }} 条检查与无法判断的文本</summary>
        <article v-for="(claim,index) in claims" :key="index" class="claim">
          <strong>{{ labels[claim.verdict] || '状态待确认' }} · {{ categories[claim.category] || '其他' }}</strong>
          <blockquote>{{ claim.text }}</blockquote><p>{{ claim.reason }}</p>
        </article>
      </details>
      <p class="muted">这些结果供人工核实，尚未纳入“一次纠错”的原规则准入；原方案和原报告保留。</p>
    </template>
  </section>
</template>
<style scoped>
.source-review{min-width:0;overflow-wrap:anywhere}.review-heading{display:flex;gap:12px;align-items:center;justify-content:space-between;flex-wrap:wrap}.review-heading h2{margin:0}.review-heading button{flex-shrink:0}.review-summary{padding:12px;border-left:3px solid #bd8c31;background:#fff7e8}.claim{padding:12px 0;border-bottom:1px solid #e6ece8}.claim strong{font-size:13px}.claim p{font-size:13px;margin:7px 0}.claim blockquote{margin:8px 0;padding:8px 12px;background:#f4f7f5;border-left:3px solid #a7bdb2;white-space:pre-wrap}.alert-claim{margin:10px 0;padding:12px;background:#fffaf0}.source-review details{margin:16px 0}@media(max-width:600px){.review-heading{align-items:flex-start;flex-direction:column}.claim blockquote{padding:8px}}
</style>
