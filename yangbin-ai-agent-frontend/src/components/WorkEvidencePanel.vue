<script setup>
import { computed, ref, watch } from 'vue'
import { downloadMarkdown } from '../downloads'
import { useRouter } from 'vue-router'
import { api, errorMessage } from '../workspace-api'
import { codingApi } from '../api/coding'
import { modelApi } from '../api/models'

const props = defineProps({resource:Object, partner:String, model:String, reportId:String})
const router = useRouter()
const root = '/ai/work/evidence'
const report = ref(null), reports = ref([]), sourceOpen = ref(false)
const idColumn = ref(''), amountColumn = ref(''), reportedTotal = ref('')
const claimTotal=ref(''), claimDifference=ref(''), claimRow=ref(''), claimOrderId=ref(''), claimAmount=ref(''), verification=ref(null)
const selection = ref([]), includeTotals = ref(false), purpose = ref('ANALYZE'), allowTests = ref(false)
const projects = ref([]), project = ref(''), profiles = ref([]), profile = ref('system')
const destination = ref(null), confirmed = ref(false), granting = ref(false), transfer = ref(null)
const busy = ref(false), error = ref('')
let destinationTicket = 0, loaded = false
const requests = new Map()
const chosen = computed(() => report.value?.rows.filter(row => selection.value.includes(row.row)) || [])
function idFor(payload) {
  const key = JSON.stringify(payload)
  if (!requests.has(key)) requests.set(key, crypto.randomUUID())
  return requests.get(key)
}
function selectReport(value) {
  report.value = value
  selection.value = []
  granting.value = false
  transfer.value = null
  sourceOpen.value = false; verification.value = null
}
async function openPanel(event) {
  if (!event.target.open || loaded || busy.value) return
  busy.value = true
  try {
    const {data} = props.reportId
      ? {data:[(await api.get(root + '/reports/' + encodeURIComponent(props.reportId))).data]}
      : await api.get(root + '/reports', {params:{partnerId:props.partner,resourceId:props.resource.id}})
    reports.value = data
    if (data.length) {
      selectReport(data[0])
      const grants=await api.get(root + '/transfers',{params:{reportId:report.value.id}})
      transfer.value=grants.data.find(grant=>!grant.revoked && Date.parse(grant.expiresAt)>Date.now()) || null
    }
    loaded = true
  } catch (err) { error.value = errorMessage(err) }
  finally { busy.value = false }
}
function exportReport() {
  const r = report.value
  const lines = [r.filename+' · 订单金额核对','', '来源版本 '+r.sourceVersion, '原始合计 '+r.actualTotal, '脚本报告 '+r.reportedTotal, '差额 '+r.difference,'']
  for(const row of r.rows)lines.push('第 '+row.row+' 行 · '+row.orderId+' · '+row.amount)
  downloadMarkdown(lines.join('\n'), r.filename+'.核对.md')
}
async function verifyClaim() {
  busy.value=true; error.value=''; verification.value=null
  try {
    const {data}=await api.post(root+'/reports/'+report.value.id+'/verify',{claimedTotal:claimTotal.value,claimedDifference:claimDifference.value,claimedRow:Number(claimRow.value),claimedOrderId:claimOrderId.value,claimedAmount:claimAmount.value})
    verification.value=data
  } catch(err) { error.value=errorMessage(err) }
  finally { busy.value=false }
}
watch([claimTotal,claimDifference,claimRow,claimOrderId,claimAmount],()=>{verification.value=null})
async function calculate() {
  if (busy.value) return
  busy.value = true; error.value = ''
  try {
    const payload = {partnerId:props.partner,resourceId:props.resource.id,idColumn:idColumn.value.trim(),amountColumn:amountColumn.value.trim(),reportedTotal:reportedTotal.value.trim()}
    const {data} = await api.post(root + '/reports', {...payload,requestId:idFor(payload)})
    reports.value = [data,...reports.value.filter(x => x.id !== data.id)]
    selectReport(data)
  } catch (err) { error.value = errorMessage(err) }
  finally { busy.value = false }
}
async function loadDestination() {
  const ticket = ++destinationTicket
  destination.value = null; confirmed.value = false
  try {
    const {data} = await api.get(root + '/destination', {params:{modelProfileId:profile.value}})
    if (ticket === destinationTicket) destination.value = data
  } catch (err) { if (ticket === destinationTicket) error.value = errorMessage(err) }
}
async function prepare() {
  if (busy.value) return
  busy.value = true; error.value = ''
  try {
    const [items, models] = await Promise.all([codingApi.projects(), modelApi.list()])
    projects.value = items; profiles.value = models.profiles || []
    project.value = items[0]?.id || ''
    profile.value = props.model || 'system'
    granting.value = true
    await loadDestination()
  } catch (err) { error.value = errorMessage(err) }
  finally { busy.value = false }
}
watch(profile, () => { if (granting.value) loadDestination() })
watch([project, purpose, selection, includeTotals, allowTests], () => { confirmed.value = false }, {deep:true})
watch(purpose, value => { if (value !== 'PROPOSE_PATCH') allowTests.value = false })
async function grant() {
  if (busy.value || !confirmed.value || !chosen.value.length || !destination.value) return
  busy.value = true; error.value = ''
  try {
    const payload = {reportId:report.value.id,sourceVersion:report.value.sourceVersion,rows:chosen.value.map(r=>r.row),projectId:project.value,modelProfileId:profile.value,purpose:purpose.value,includeTotals:includeTotals.value,allowTests:allowTests.value,confirmExternal:true,destinationVersion:destination.value.version}
    const {data} = await api.post(root + '/transfers', {...payload,requestId:idFor(payload)})
    transfer.value = data; granting.value = false
  } catch (err) { error.value = errorMessage(err) }
  finally { busy.value = false }
}
async function revoke() {
  busy.value = true
  try { await api.post(root + '/transfers/' + transfer.value.id + '/revoke'); transfer.value=null; requests.clear() }
  catch (err) { error.value=errorMessage(err) }
  finally { busy.value=false }
}
function openCoding() {
  router.push({path:'/coding',query:{project:transfer.value.projectId,transfer:transfer.value.id}})
}
</script>
<template>
  <details class="work-evidence" :open="reportId ? true : undefined" @toggle="openPanel">
    <summary>核对订单金额 · {{ resource.name }}</summary>
    <p v-if="error" role="alert" class="coding-error">{{error}}</p>
    <div v-if="!report">
      <label>订单号列名<input v-model="idColumn" placeholder="例如 order_id"/></label>
      <label>金额列名<input v-model="amountColumn" placeholder="例如 amount"/></label>
      <label>脚本报告的总额<input v-model="reportedTotal" inputmode="decimal" placeholder="例如 3320"/></label>
      <button type="button" :disabled="busy || !idColumn.trim() || !amountColumn.trim() || !reportedTotal.trim()" @click="calculate">按原始行复算</button>
    </div>
    <template v-else>
      <p><strong>差额 {{report.difference}}</strong> · 原始合计 {{report.actualTotal}} · 脚本报告 {{report.reportedTotal}}</p>
      <button type="button" @click="sourceOpen=!sourceOpen" :aria-expanded="sourceOpen">查看来源与选区（{{report.rows.length}} 行）</button>
      <button type="button" :disabled="busy" @click="selectReport(null)">重新选择核对口径</button>
      <button type="button" @click="exportReport">下载核对报告</button>
      <details><summary>核验报告中引用的数字与原行</summary>
        <label>引用的原始合计<input v-model="claimTotal" inputmode="decimal"/></label>
        <label>引用的差额<input v-model="claimDifference" inputmode="decimal"/></label>
        <label>引用的行号<input v-model="claimRow" inputmode="numeric"/></label>
        <label>引用的订单号<input v-model="claimOrderId"/></label>
        <label>引用的金额<input v-model="claimAmount" inputmode="decimal"/></label>
        <button type="button" :disabled="busy || !claimTotal || !claimDifference || !claimRow || !claimOrderId || !claimAmount" @click="verifyClaim">与固定来源比对</button>
        <p v-if="verification" role="status">{{verification.verified?'数字与原行均已核对':'引用未通过核对'}} <span v-if="!verification.verified">{{verification.failedChecks.join('、')}}</span></p>
      </details>
      <p v-if="report.matchingRows?.length">与差额相等的行号：{{report.matchingRows.join('、')}}。脚本原因仍需查证。</p>
      <div v-if="sourceOpen || granting" class="evidence-rows" tabindex="0" aria-label="选取固定来源的订单行">
        <small :title="report.sourceVersion">来源版本 {{report.sourceVersion.slice(0,12)}} · {{resource.name}}</small>
        <label v-for="row in report.rows" :key="row.row"><input type="checkbox" v-model="selection" :value="row.row"/>第 {{row.row}} 行 · {{row.orderId}} · {{row.amount}}</label>
      </div>
      <button v-if="!granting && !transfer" type="button" :disabled="busy" @click="prepare">选择材料交给 Coding</button>
      <fieldset v-if="granting"><legend>本次授权</legend>
        <label>目标项目<select v-model="project" aria-label="目标项目"><option v-for="p in projects" :key="p.id" :value="p.id">{{p.name}}</option></select></label>
        <p v-if="!projects.length">请先在 Coding 创建目标项目。</p>
        <label>模型接收方<select v-model="profile" aria-label="模型接收方"><option value="system">系统默认</option><option v-for="p in profiles.filter(x=>x.id!=='system')" :value="p.id" :key="p.id">{{p.name}}</option></select></label>
        <p v-if="destination">{{destination.provider}} · {{destination.model}} · {{destination.baseUrl}}</p>
        <label>用途<select v-model="purpose" aria-label="用途"><option value="ANALYZE">只分析</option><option value="PROPOSE_PATCH">生成候选修复</option></select></label>
        <label><input type="checkbox" v-model="includeTotals"/>同时发送报告的合计和差额</label>
        <label v-if="purpose==='PROPOSE_PATCH'"><input type="checkbox" v-model="allowTests"/>允许在无网络沙箱内测试候选</label>
        <label><input type="checkbox" v-model="confirmed"/>同意将所选 {{chosen.length}} 行按上述用途发给该接收方</label>
        <button type="button" class="primary" :disabled="busy || !confirmed || !chosen.length || !project || !destination" @click="grant">授权本次转交</button>
        <button type="button" @click="granting=false">取消</button>
      </fieldset>
      <div v-if="transfer"><button type="button" class="primary" @click="openCoding">打开 Coding 草稿</button><button type="button" :disabled="busy" @click="revoke">撤销转交</button></div>
    </template>
  </details>
</template>
<style scoped>
.work-evidence { margin:.65rem 0; padding:.8rem 1rem; border:1px solid #d7e0eb; border-radius:12px; background:var(--surface,#fff); color:var(--text,#26384a); font:inherit; font-size:.92rem; line-height:1.5; overflow-wrap:anywhere; }
.work-evidence > summary { cursor:pointer; font-weight:650; }
.work-evidence p { margin:.5rem 0; }
.work-evidence button,.work-evidence input:not([type=checkbox]),.work-evidence select { font:inherit; border:1px solid #ccd7e3; border-radius:7px; background:var(--surface,#fff); color:inherit; }
.work-evidence button { margin:.35rem .45rem .2rem 0; padding:.32rem .7rem; cursor:pointer; }
.work-evidence button:disabled { opacity:.55; cursor:default; }
.work-evidence label { display:block; margin:.4rem 0; font-weight:500; }
.work-evidence input:not([type=checkbox]),.work-evidence select { display:block; width:min(100%,24rem); padding:.35rem .55rem; margin-top:.2rem; box-sizing:border-box; }
.work-evidence input[type=checkbox] { margin-inline:0 .45rem; accent-color:#17645c; }
.work-evidence fieldset { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:.15rem 1rem; padding:.5rem .9rem .8rem; margin:.8rem 0 .2rem; border:1px solid #d7e0eb; border-radius:9px; min-width:0; }
.work-evidence legend { padding:0 .35rem; font-weight:650; }
.work-evidence fieldset p,.work-evidence fieldset label:has(input[type=checkbox]) { grid-column:1/-1; }
.work-evidence fieldset button { justify-self:start; }
.work-evidence .primary { background:#175c57; color:#fff; border-color:#175c57; }
.evidence-rows { max-height:12rem; overflow:auto; padding:.45rem .65rem; background:var(--surface-subtle,#f4f7fa); border-radius:7px; }
.evidence-rows label { margin:.2rem 0; }
.work-evidence :focus-visible { outline:2px solid #217f89; outline-offset:2px; }
@media(max-width:600px) { .work-evidence fieldset { grid-template-columns:1fr; } }
@media(prefers-color-scheme:dark) { .work-evidence { --surface:#1c2632; --surface-subtle:#253240; --text:#eef3f7; } }
</style>
