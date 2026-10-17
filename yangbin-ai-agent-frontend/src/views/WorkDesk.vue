<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, errorMessage } from '../workspace-api'
import { auth } from '../auth'
import StudioIcon from '../components/StudioIcon.vue'
import '../studio.css'
const props = defineProps({ embeddedPartner: Object })
const route = useRoute(), router = useRouter()
const catalog = ref({skills:[],partners:[]}), runs = ref([]), run = ref(null), prompt = ref('')
const exporting = ref(false)
const loading = ref(true), busy = ref(false), error = ref(''), notice = ref(''), uncertain = ref(false), search = ref(''), section = ref('tasks')
const skillId = computed(() => props.embeddedPartner?.skillId || route.params.skillId || 'weekly-report')
const skill = computed(() => catalog.value.skills.find(s => s.id === skillId.value && s.kind === 'TEXT'))
const isPresentation = computed(() => skill.value?.id === 'presentation-outline' && skill.value?.version >= 2)
const partner = computed(() => catalog.value.partners.find(p => p.id === (props.embeddedPartner?.id || route.query.partner) && p.skillId === skillId.value))
const validPartner = computed(() => !!props.embeddedPartner || !route.query.partner || !!partner.value)
const owner = () => auth.user?.id || ''
const title = computed(() => partner.value?.name || skill.value?.name || '工作任务')
const filteredRuns = computed(() => runs.value.filter(r => r.skillId === skillId.value && (!partner.value || r.partnerId === partner.value.id) && [r.title,r.skillName].join(' ').includes(search.value.trim())))
const activeElsewhere = computed(() => runs.value.some(r => r.status === 'RUNNING'))
const statuses = { RUNNING:'正在生成',SUCCEEDED:'已完成',FAILED:'未完成',INTERRUPTED:'已中断' }
let exportEpoch = 0, exportController
let epoch = 0, listEpoch = 0, submitEpoch = 0, controller, alive = true, poll, pendingId = ''
async function load() {
  const current = ++epoch, account = owner()
  controller?.abort(); controller = new AbortController()
  loading.value = true; error.value = ''; notice.value = ''; busy.value = false; uncertain.value = false; run.value = null; runs.value = []; prompt.value = ''; pendingId = ''; catalog.value = {skills:[],partners:[]}
  try {
    const [c,history] = await Promise.all([api.get('/ai/work/catalog',{ signal:controller.signal }),api.get('/ai/work/runs',{ signal:controller.signal })])
    if (!alive || current !== epoch || account !== owner()) return
    catalog.value = c.data; runs.value = history.data
    if (route.query.run) {
      const id = String(route.query.run), {data} = await api.get('/ai/work/runs/' + encodeURIComponent(id),{ signal:controller.signal })
      if (!alive || current !== epoch || account !== owner()) return
      if (data.id !== id || data.skill.id !== skillId.value || (props.embeddedPartner && data.partner?.id !== props.embeddedPartner.id)) throw new Error('Run binding mismatch')
      run.value = data
    }
  } catch (e) { if (alive && current === epoch && account === owner() && e.code !== 'ERR_CANCELED') error.value = errorMessage(e) }
  finally { if (alive && current === epoch) loading.value = false }
}
async function refreshRuns() {
  const current = epoch, seq = ++listEpoch, account = owner()
  try {
    const {data} = await api.get('/ai/work/runs',{ signal:controller?.signal,timeout:20000 })
    if (!alive || current !== epoch || seq !== listEpoch || account !== owner()) return
    runs.value = data
    const id = pendingId || (run.value?.status === 'RUNNING' ? run.value.id : '')
    if (id && data.some(r => r.id === id)) {
      const response = await api.get('/ai/work/runs/' + encodeURIComponent(id),{ signal:controller?.signal })
      if (!alive || current !== epoch || seq !== listEpoch || account !== owner() || (pendingId || run.value?.id) !== id) return
      if (response.data.id !== id || response.data.skill.id !== skillId.value) throw new Error('Run binding mismatch')
      run.value = response.data
      if (run.value.status !== 'RUNNING') { uncertain.value = false; pendingId = ''; busy.value = false; notice.value = '任务状态已更新。' }
    }
  } catch (e) { if (alive && current === epoch && seq === listEpoch && account === owner() && e.code !== 'ERR_CANCELED') error.value = errorMessage(e) }
}
function requestId() {
  if (globalThis.crypto?.randomUUID) return crypto.randomUUID()
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  bytes[6] = (bytes[6] & 15) | 64; bytes[8] = (bytes[8] & 63) | 128
  const hex = Array.from(bytes, value => value.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}
async function submit() {
  if (busy.value || uncertain.value || activeElsewhere.value || !skill.value || !validPartner.value || !prompt.value.trim()) return
  const current = epoch, account = owner(), id = requestId(), submission = ++submitEpoch
  pendingId = id; busy.value = true; error.value = ''; notice.value = ''; run.value = null
  const input = { requestId:id,skillId:skill.value.id,partnerId:partner.value?.id || null,prompt:prompt.value }
  try {
    const {data} = await api.post('/ai/work/runs',input,{ signal:controller.signal,timeout:180000 })
    if (!alive || current !== epoch || submission !== submitEpoch || account !== owner()) return
    if (data.id !== id || data.skill.id !== input.skillId) throw new Error('Run binding mismatch')
    run.value = data; prompt.value = ''; pendingId = data.status === 'RUNNING' ? id : ''
    uncertain.value = data.status === 'RUNNING'
    await refreshRuns()
  } catch (e) {
    if (!alive || current !== epoch || submission !== submitEpoch || account !== owner()) return
    uncertain.value = !e.response || e.response.status >= 500
    if (!uncertain.value) pendingId = ''
    error.value = uncertain.value ? '连接未完成，服务端可能仍在生成。请刷新任务记录确认状态，不要重复提交。' : errorMessage(e)
    await refreshRuns()
  } finally { if (alive && current === epoch && submission === submitEpoch && account === owner()) busy.value = false }
}
function openRun(item) { if(props.embeddedPartner) { router.push({path:'/partners/'+props.embeddedPartner.id,query:{run:item.id}}); return } router.push({path:'/work/' + item.skillId,query:{run:item.id,...(item.partnerId ? {partner:item.partnerId} : {})}}) }
async function copy() {
  const current = epoch
  try { await navigator.clipboard.writeText(run.value.result); if (current === epoch) notice.value = '已复制文稿。' }
  catch { if (current === epoch) notice.value = '当前浏览器无法复制，请选中文稿后手动复制。' }
}
function download() {
  const blob = new Blob([run.value.result],{type:'text/markdown;charset=utf-8'}), url = URL.createObjectURL(blob)
  const a = document.createElement('a'); a.href = url; a.download = 'yangbin-' + run.value.id + '.md'; a.click()
  setTimeout(() => URL.revokeObjectURL(url),1000)
}
async function downloadPresentation() {
  if (!run.value?.presentation || exporting.value) return
  const id = run.value.id, current = epoch, ticket = ++exportEpoch, account = owner()
  exportController?.abort(); exportController = new AbortController()
  exporting.value = true; notice.value = ''
  const valid = () => alive && current === epoch && ticket === exportEpoch && account === owner() && run.value?.id === id
  try {
    const path = '/ai/work/runs/' + encodeURIComponent(id) + '/presentation'
    const {data:artifact} = await api.post(path,{}, {signal:exportController.signal,timeout:30000})
    if (!valid()) return
    if (artifact.runId !== id || !Number.isInteger(artifact.slideCount)) throw new Error('Artifact binding mismatch')
    const {data} = await api.get(path,{responseType:'blob',signal:exportController.signal,timeout:30000})
    if (!valid()) return
    if (!(data instanceof Blob) || data.size !== artifact.bytes || data.size < 22) throw new Error('Invalid presentation file')
    const url = URL.createObjectURL(data), a = document.createElement('a')
    a.href = url; a.download = 'yangbin-' + id + '.pptx'; a.click(); setTimeout(() => URL.revokeObjectURL(url),1000)
    notice.value = '已下载 ' + artifact.slideCount + ' 页可编辑 PPTX，讲稿保存在演讲者备注中。'
  } catch (e) { if (valid() && e.code !== 'ERR_CANCELED') notice.value = 'PPTX 下载未完成，请重试生成文件；不会重新调用模型。' }
  finally { if (valid()) exporting.value = false }
}
watch(() => run.value?.id, () => { exportEpoch++; exportController?.abort(); exporting.value = false })
function time(value) { return new Date(value).toLocaleString('zh-CN',{hour12:false}) }
watch(() => [route.params.skillId,route.query.partner,route.query.run,owner()],load)
onMounted(() => { load(); poll = setInterval(() => { if (!loading.value && (pendingId || activeElsewhere.value)) refreshRuns() },4000) })
onBeforeUnmount(() => { alive = false; epoch++; controller?.abort(); exportController?.abort(); clearInterval(poll) })
</script>
<template>
  <main :class="['studio work-desk',{'embedded-desk':embeddedPartner}]" data-testid="work-desk">
    <header v-if="!embeddedPartner" class="studio-top desk-top"><div class="work-breadcrumb"><RouterLink to="/skills?tab=partners">工作空间</RouterLink><span>›</span><strong>{{ title }}</strong></div><RouterLink class="studio-btn" to="/skills">切换技能 <StudioIcon name="grid" :size="15" /></RouterLink></header>
    <div class="desk-body">
      <p v-if="loading" class="studio-note" role="status">正在读取工作空间…</p>
      <div v-if="error" class="studio-error" role="alert">{{ error }} <button class="text-action" @click="refreshRuns">刷新任务记录</button></div>
      <div v-if="!loading && (!skill || !validPartner)" class="studio-empty"><h2>没有找到这个技能或伙伴</h2><RouterLink class="studio-btn" to="/skills">返回技能广场</RouterLink></div>
      <template v-if="skill && validPartner">
        <section v-if="!embeddedPartner" class="desk-intro">
          <div class="capability-icon desk-avatar" :class="[skill.color,{'partner-avatar':partner}]"><span v-if="partner">{{ partner.initial }}</span><StudioIcon v-else :name="skill.icon" :size="32" /></div>
          <div><div class="desk-eyebrow">{{ skill.category }} / 你的专属工作空间</div><h1>{{ title }}</h1><p>{{ partner?.description || skill.description }}</p></div>
        </section>
        <div v-if="isPresentation" class="narrative-entry"><div><strong>使用 NarrativePPT 完整工作台</strong><p>导入文档 · 听众与叙事规划 · 大纲审阅 · 逐页编辑 · 主题与 PPTX</p></div><RouterLink class="studio-btn primary" to="/presentation">打开演示工作台 →</RouterLink></div>
        <form class="work-composer" @submit.prevent="submit">
          <label class="sr-only" for="work-prompt">任务需求与材料</label>
          <textarea id="work-prompt" v-model="prompt" :disabled="busy || uncertain" maxlength="12000" :placeholder="skill.example" required></textarea>
          <div class="composer-footer"><span>{{ isPresentation ? '粘贴需求与材料 · 生成演示内容与 PPTX' : '粘贴需求与材料 · 交付 Markdown 文稿' }}</span><div><small>{{ prompt.length }}/12000</small><button class="submit-work" type="submit" :disabled="busy || uncertain || activeElsewhere || !prompt.trim()" :aria-label="busy ? '正在生成文稿' : '生成文稿'"><StudioIcon v-if="!busy" name="arrow" :size="22" /><span v-else class="working-dot"></span></button></div></div>
        </form>
        <div class="composer-extras"><button class="example-task" :disabled="busy || uncertain" @click="prompt = skill.example"><StudioIcon name="spark" :size="14" />试试示例任务</button><span>{{ skill.output }}</span></div>
        <p v-if="busy || activeElsewhere || uncertain" class="studio-note execution-note" role="status">{{ busy ? '正在生成并保存成果，请稍候。离开页面后可从任务记录查看结果。' : uncertain ? '正在确认任务状态；不会自动再次调用模型。' : '当前账号有文本任务正在执行，完成后可继续新建。' }}</p>
        <p v-if="notice" class="studio-note" role="status">{{ notice }}</p>
        <section v-if="run" class="work-result" aria-label="任务成果">
          <div class="result-heading"><div><span class="run-status" :class="run.status.toLowerCase()">{{ statuses[run.status] || run.status }}</span><h2>{{ run.title }}</h2><p>技能 v{{ run.skill.version }} · {{ time(run.createdAt) }}</p></div><div v-if="run.result" class="result-actions"><button v-if="run.presentation" class="studio-btn primary" :disabled="exporting" @click="downloadPresentation"><StudioIcon name="slides" :size="14" />{{ exporting ? '正在生成文件…' : '下载 PPTX' }}</button><button class="studio-btn" @click="copy"><StudioIcon name="copy" :size="14" />复制</button><button class="studio-btn" @click="download"><StudioIcon name="download" :size="14" />下载 .md</button></div></div>
          <p v-if="run.error" class="studio-error">{{ run.error }}</p>
          <p v-if="run.presentation" class="studio-note" style="margin-top:16px">{{ run.presentation.slides.length }} 页逐页内容已保存；PPTX 包含可编辑文字和演讲者备注，下载不再次调用模型。</p><p v-else-if="run.skill.id === 'presentation-outline' && run.status === 'SUCCEEDED'" class="studio-note" style="margin-top:16px">这是旧版文字任务，没有逐页结构。可保留原文稿，使用当前演示技能新建任务以生成 PPTX。</p><pre v-if="run.result" class="result-document">{{ run.result }}</pre>
          <p v-else-if="run.status === 'RUNNING'" class="studio-note">任务已保存，正在等待生成结果…</p>
          <details class="run-input"><summary>查看当时的输入与技能指引</summary><h3>输入材料</h3><pre>{{ run.prompt }}</pre><h3>执行指引 · v{{ run.skill.version }}</h3><p>{{ run.skill.instructions }}</p></details>
        </section>
        <div class="desk-section-bar"><div><button :class="{active:section === 'tasks'}" @click="section = 'tasks'">任务记录 <small>{{ filteredRuns.length }}</small></button><button v-if="embeddedPartner" :class="{active:section === 'automation'}" @click="section = 'automation'">自动化</button><button v-if="embeddedPartner" :class="{active:section === 'resources'}" @click="section = 'resources'">资源</button><button v-else :class="{active:section === 'about'}" @click="section = 'about'">能力说明</button></div><label v-if="section === 'tasks'" class="studio-search"><StudioIcon name="search" :size="16" /><input v-model="search" aria-label="搜索任务记录" placeholder="搜索任务"></label></div>
        <section v-if="section === 'tasks'" aria-label="任务记录">
          <div v-if="!filteredRuns.length" class="studio-empty"><StudioIcon name="chat" :size="34" /><h2>{{ search ? '没有匹配的任务' : '第一份成果，从这里开始' }}</h2><p>{{ search ? '试试其他关键词。' : '描述你想完成的事，生成后的记录会保存在这里。' }}</p></div>
          <button v-for="item in filteredRuns" :key="item.id" class="work-history-row" @click="openRun(item)"><div class="history-icon"><StudioIcon name="document" :size="19" /></div><div><strong>{{ item.title }}</strong><span>{{ time(item.createdAt) }}</span></div><span class="run-status" :class="item.status.toLowerCase()">{{ statuses[item.status] || item.status }}</span><StudioIcon name="arrow" :size="16" /></button>
          <div v-if="!embeddedPartner && runs.some(r => r.skillId !== skillId)" class="other-work"><h3>其他技能的近期任务</h3><button v-for="item in runs.filter(r => r.skillId !== skillId).slice(0,5)" :key="item.id" @click="openRun(item)">{{ item.skillName }} · {{ item.title }} <span>{{ statuses[item.status] }}</span></button></div>
        </section>
        <section v-else-if="section === 'automation'" class="studio-empty"><h2>暂未接入定时自动化</h2><p>当前由你提交需求后执行，已有任务会持续保留。</p></section>
        <section v-else-if="section === 'resources'" class="work-about"><h2>任务文稿</h2><p>已完成任务的文稿可以打开后复制或下载。</p><p v-if="!filteredRuns.some(r=>r.status==='SUCCEEDED')">暂无已完成的文稿。</p><button v-for="item in filteredRuns.filter(r=>r.status==='SUCCEEDED')" :key="item.id" class="work-history-row" @click="openRun(item)">{{item.title}} →</button></section>
        <section v-else class="work-about"><h2>提供材料，获得一份可编辑的初稿</h2><p>{{ skill.instructions }}</p><div class="about-delivery"><StudioIcon name="document" :size="20" /><strong>{{ skill.output }}</strong></div><p>当前基于粘贴的文本生成，不自动访问网页或知识库，也不运行数据分析代码。{{ isPresentation ? '可将逐页内容导出为可编辑PPTX，讲稿写入演讲者备注；当前不生成图表或配图。' : '图片、视频与 PPTX 文件生成尚未接入此技能。' }}</p><p>每次提交使用当时的技能版本，任务记录保留原始输入与结果。伙伴是角色与工作方法的入口，当前不进行多 Agent 协作。</p></section>
      </template>
    </div>
  </main>
</template>
<style scoped>
.embedded-desk{min-height:0}.embedded-desk .desk-body{padding:0;max-width:none}.embedded-desk .desk-section-bar{margin-top:50px}
.narrative-entry{display:flex;align-items:center;justify-content:space-between;gap:18px;flex-wrap:wrap;padding:20px;background:#f5f7ff;border:1px solid #e1e7fb;border-radius:14px;margin-bottom:22px}.narrative-entry strong{font-size:14px;color:#334568}.narrative-entry p{font-size:12px;color:#7a88a0;margin-top:9px;line-height:1.8}

.desk-top{min-height:78px}.work-breadcrumb{display:flex;align-items:center;gap:14px;font-size:14px;min-width:0}.work-breadcrumb a,.work-breadcrumb span{color:#9ba4b2}.work-breadcrumb strong{font-weight:500;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.desk-body{max-width:1120px;margin:auto;padding:36px 55px 70px}.desk-intro{display:flex;gap:22px;align-items:center;margin:62px 0 32px}.desk-avatar{width:74px;height:74px;flex-basis:74px;font-size:28px}.desk-eyebrow{font-size:11px;color:#9ca7b7;letter-spacing:1px;margin-bottom:9px}.desk-intro h1{font-size:29px;letter-spacing:-.8px;font-weight:600;margin-bottom:12px}.desk-intro p{color:#8f9aaa;font-size:13px;line-height:1.8}.work-composer{border:1px solid #e2e7ef;border-radius:22px;background:white;box-shadow:0 5px 25px #283d6506;overflow:hidden;padding:20px 23px 15px}.work-composer:focus-within{border-color:#a9bdeb;box-shadow:0 5px 25px #3765e60c}.work-composer textarea{border:0;background:none;outline:none!important;width:100%;min-height:150px;resize:vertical;line-height:1.9;font-size:15px;color:#303c50}.work-composer textarea::placeholder{color:#a8b0bd}.composer-footer{display:flex;align-items:center;justify-content:space-between;gap:12px;padding-top:12px}.composer-footer>span{font-size:11px;color:#a2aab8}.composer-footer>div{display:flex;gap:13px;align-items:center}.composer-footer small{font-size:10px;color:#a8b0bc}.submit-work{border:0;background:#3765e6;color:#fff;display:grid;place-items:center;width:39px;height:39px;border-radius:50%}.submit-work svg{transform:rotate(-90deg)}.working-dot{width:12px;height:12px;background:#fff;border-radius:3px;animation:pulse 1s infinite}.composer-extras{display:flex;gap:14px;justify-content:space-between;align-items:center;margin-top:14px}.example-task{border:1px solid #e9ecf2;background:#fafbfd;color:#8090a8;display:flex;align-items:center;gap:6px;padding:7px 11px;border-radius:7px;font-size:11px}.composer-extras>span{font-size:11px;color:#a4acb8;text-align:right}.execution-note{margin-top:15px}.desk-section-bar{margin-top:65px;padding-bottom:14px;display:flex;align-items:center;justify-content:space-between;gap:15px;border-bottom:1px solid #eceff4}.desk-section-bar>div{display:flex;gap:25px}.desk-section-bar button{border:0;background:none;color:#a0a8b5;padding:8px 0;font-size:14px}.desk-section-bar button.active{color:#303b51}.desk-section-bar small{font-size:10px;color:#99a4b8;background:#f1f4f9;padding:2px 5px;border-radius:5px;margin-left:5px}.desk-section-bar .studio-search{font-size:12px;width:210px}.work-history-row{display:flex;width:100%;align-items:center;gap:16px;padding:20px 0;background:none;border:0;border-bottom:1px solid #eef1f5;text-align:left;color:#63748b}.history-icon{background:#f4f6fa;border-radius:8px;padding:10px;display:flex}.work-history-row>div:nth-child(2){flex:1;min-width:0}.work-history-row strong{font-size:13px;font-weight:500;display:block;overflow:hidden;white-space:nowrap;text-overflow:ellipsis;color:#445168}.work-history-row div>span{font-size:10px;color:#a4aebc;display:block;margin-top:7px}.run-status{font-size:10px;border-radius:5px;padding:4px 7px;background:#f1f4f8;color:#7b8da4;white-space:nowrap}.run-status.succeeded{background:#edf7f2;color:#4c9a7a}.run-status.failed,.run-status.interrupted{background:#fff2ec;color:#bf8962}.run-status.running{background:#edf2ff;color:#6685cf}.work-result{margin-top:32px;border:1px solid #e4e9f1;border-radius:14px;padding:25px}.result-heading{display:flex;justify-content:space-between;align-items:center;gap:15px}.result-heading>div:first-child{min-width:0}.result-heading h2{font-size:17px;margin-top:12px;line-height:1.6;font-weight:550}.result-heading p{font-size:10px;color:#a0aaba;margin:8px 0}.result-actions{display:flex;gap:7px;flex-shrink:0;flex-wrap:wrap}.result-heading{flex-wrap:wrap}.result-document{white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.9;font-family:inherit;font-size:14px;color:#48566b;border-top:1px solid #edf0f5;margin-top:18px;padding-top:24px}.run-input{margin-top:25px;color:#9aa6b8;font-size:11px}.run-input summary{cursor:pointer}.run-input h3{font-size:12px;margin:18px 0 10px}.run-input pre{white-space:pre-wrap;font:inherit}.run-input p{line-height:1.8}.work-about{padding:30px 0}.work-about h2{font-size:18px;font-weight:550;margin-bottom:20px}.work-about p{font-size:13px;line-height:1.9;color:#8c98a9;margin:15px 0}.about-delivery{display:flex;align-items:center;gap:10px;color:#6981b1;background:#f5f7fc;padding:18px;border-radius:10px;font-size:13px}.other-work{padding:20px 0;border-top:1px solid #edf0f5}.other-work h3{font-size:12px;color:#98a2b3;margin:12px 0}.other-work button{display:block;width:100%;text-align:left;font-size:12px;border:0;background:none;color:#7e8ca3;padding:10px 0;overflow-wrap:anywhere}.other-work button span{font-size:10px;margin-left:10px;color:#a6afbb}.sr-only{position:absolute;width:1px;height:1px;overflow:hidden;clip:rect(0,0,0,0)}
@keyframes pulse{50%{opacity:.4}}@media(max-width:1100px){.desk-body{padding:30px}.desk-intro{margin-top:45px}}@media(max-width:760px){.desk-top{min-height:65px;padding:15px 18px;flex-wrap:nowrap}.desk-top .studio-btn{padding:8px;font-size:10px!important}.work-breadcrumb{font-size:12px;gap:9px}.desk-body{padding:22px 18px 40px}.desk-intro{margin:26px 0;gap:15px}.desk-intro h1{font-size:24px}.desk-avatar{width:58px;height:58px;flex-basis:58px}.desk-eyebrow{font-size:9px}.desk-intro p{font-size:12px}.work-composer{padding:16px;border-radius:17px}.work-composer textarea{min-height:170px;font-size:14px}.composer-footer>span{font-size:9px;max-width:140px;line-height:1.6}.composer-extras{align-items:flex-start}.composer-extras>span{font-size:10px;max-width:140px;line-height:1.7}.example-task{font-size:10px}.desk-section-bar{margin-top:40px;flex-wrap:wrap}.desk-section-bar .studio-search{width:140px;padding:8px}.desk-section-bar>div{gap:15px}.desk-section-bar button{font-size:12px}.work-result{padding:17px}.result-heading{flex-direction:column;align-items:flex-start}.work-history-row{gap:10px}.history-icon{padding:8px}.work-history-row>svg{display:none}}
</style>
