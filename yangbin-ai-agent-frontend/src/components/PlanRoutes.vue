<script setup>
import { ref, watch, onBeforeUnmount } from 'vue'
import { api, errorMessage } from '../workspace-api'
import { auth } from '../auth'
const props = defineProps({ planId: String, version: Number })
const report = ref(null), busy = ref(false), error = ref('')
const owner = () => auth.user?.id || auth.user?.username || ''
let epoch = 0, controller, alive = true
function reset() { epoch++; controller?.abort(); report.value = null; error.value = ''; busy.value = false }
watch(() => [props.planId, props.version, owner()], reset)
async function inspect() {
  if (!props.planId || !props.version || !owner() || busy.value) return
  const current = ++epoch, account = owner(), id = props.planId, version = props.version
  controller?.abort(); controller = new AbortController()
  busy.value = true; error.value = ''; report.value = null
  try {
    const { data } = await api.get('/ai/plans/' + encodeURIComponent(id) + '/' + version + '/routes',
      { signal: controller.signal, timeout: 25000 })
    if (!alive || current !== epoch || account !== owner()) return
    if (data.planId !== id || data.planVersion !== version || data.mode !== 'WALKING' || !Array.isArray(data.legs))
      throw new Error('Invalid route binding')
    report.value = data
  } catch (e) {
    if (alive && current === epoch && account === owner() && e.code !== 'ERR_CANCELED') error.value = errorMessage(e)
  } finally { if (alive && current === epoch && account === owner()) busy.value = false }
}
const minutes = seconds => Math.ceil(seconds / 60)
function distance(meters) { return meters < 1000 ? meters + ' 米' : (meters / 1000).toFixed(1) + ' 公里' }
function timestamp(value) { const date = new Date(value); return value && !Number.isNaN(date.getTime()) ? date.toLocaleString() : '未知' }
onBeforeUnmount(() => { alive = false; reset() })
</script>
<template>
  <section class="panel plan-routes" aria-label="站间步行路线" data-testid="plan-routes">
    <div class="route-heading"><h2>站间步行路线</h2>
      <button class="secondary" :disabled="busy || !planId || !version || !owner()" @click="inspect">{{ busy ? '正在查询…' : report ? '重新查询路线' : '查询步行路线' }}</button>
    </div>
    <p class="muted">查询相邻站点的步行距离与预计耗时。仅支持保存了高德地点的路段；每次点击会向地图服务发送地点坐标，最多查询 11 段。</p>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <template v-if="report">
      <p class="muted">路线查询于 {{ timestamp(report.checkedAt) }}；地点依据保存于 {{ timestamp(report.evidenceFetchedAt) }}。</p>
      <p v-if="report.requestedTransport !== '步行'" class="route-notice">原交通方式为“{{ report.requestedTransport || '未填写' }}”，以下仅作步行参考，不判断原方案能否按时完成。</p>
      <p v-if="!report.legs.length" role="status">少于两个站点，无需查询站间路线。</p>
      <article v-for="leg in report.legs" :key="leg.fromStop" class="route-leg" :class="{ 'route-warning': leg.timing === 'INSUFFICIENT_TIME' }">
        <strong>{{ leg.fromStop + 1 }} → {{ leg.toStop + 1 }} · {{ leg.fromName }} → {{ leg.toName }}</strong>
        <template v-if="leg.status === 'AVAILABLE'">
          <p class="route-metric">预计步行 {{ minutes(leg.durationSeconds) }} 分钟 · {{ distance(leg.distanceMeters) }}</p>
          <p v-if="leg.remainingAfterWalkingSeconds !== null">
            两站开始时间相隔 {{ minutes(leg.startGapSeconds) }} 分钟；
            <span v-if="leg.remainingAfterWalkingSeconds < 0">仅步行就超出约 {{ minutes(-leg.remainingAfterWalkingSeconds) }} 分钟。</span>
            <span v-else>扣除步行后剩余约 {{ Math.floor(leg.remainingAfterWalkingSeconds / 60) }} 分钟，需用于上一站活动及缓冲。</span>
          </p>
        </template>
        <p v-else class="muted">该段未获得路线数据</p>
        <p :role="leg.timing === 'INSUFFICIENT_TIME' ? 'alert' : undefined">{{ leg.message }}</p>
      </article>
      <p v-for="notice in report.notices" :key="notice" class="muted">{{ notice }}</p>
    </template>
  </section>
</template>
<style scoped>
.plan-routes{min-width:0;overflow-wrap:anywhere}.route-heading{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap}.route-heading h2{margin:0}.route-heading button{flex-shrink:0}.route-leg{padding:14px 0;border-bottom:1px solid #e6ece8}.route-leg strong{font-size:14px}.route-leg p{font-size:13px;margin:9px 0}.route-leg .route-metric{font-size:16px;font-weight:600;color:#265548}.route-warning{background:#fff7e8;border-left:3px solid #bd8c31;padding:14px;margin:10px 0}.route-notice{background:#f2f6f5;padding:12px;font-size:13px}@media(max-width:600px){.route-heading{align-items:flex-start;flex-direction:column}}
</style>
