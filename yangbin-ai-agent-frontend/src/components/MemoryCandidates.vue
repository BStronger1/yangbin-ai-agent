<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { auth } from '../auth'
import { memoryApi } from '../api/memory'
const props = defineProps({ projectId:{type:String,required:true}, disabled:Boolean, enabled:{type:Boolean,default:true} })
const emit = defineEmits(['changed','busy'])
const snapshot = ref(null), loading = ref(false), busy = ref(false), error = ref(''), notice = ref('')
let generation = 0, disposed = false
const owner = () => auth.user?.id || auth.user?.username
const valid = token => !disposed && token === generation
const items = computed(() => (snapshot.value?.candidates || []).filter(item => item.status === 'PENDING'))
const sources = { USER_EXPLICIT:'用户明确表达', MODEL_REFLECTION:'模型反思（未经核实）', TOOL_OBSERVATION:'工具观察（未经核实）' }
const uncertainty = value => ({RULE_MATCH_REQUIRES_USER_CONFIRMATION:'规则识别，仍需你确认',EXTRACTED_NEEDS_CONFIRMATION:'提取结果，仍需你确认',UNVERIFIED_NOT_USER_FACT:'未经核实，不是已确认的用户事实',CONFLICT_REQUIRES_VERSIONED_CORRECTION:'与已有条目冲突，需核对目标版本',USER_CONFIRMED:'用户已确认'})[value] || value || '未提供，需核实'
const kinds = { FACT:'事实', PREFERENCE:'偏好', CONSTRAINT:'项目约束' }
const time = value => value && Number.isFinite(Date.parse(value)) ? new Date(value).toLocaleString('zh-CN') : '未提供'
const expired = item => !Number.isFinite(Date.parse(item.expiresAt)) || Date.parse(item.expiresAt) <= Date.now()
const canAccept = item => props.enabled && item.sourceType === 'USER_EXPLICIT' && !expired(item)
async function reload(clear = true) {
  if (busy.value) return
  const token = ++generation, project = props.projectId
  loading.value = true
  if (clear) {error.value = '';notice.value = ''}
  try { const result = await memoryApi.candidates(project); if (valid(token)) snapshot.value = result }
  catch { if (valid(token)) error.value = '候选记忆未能加载。可以刷新重试；不会自动确认候选。' }
  finally { if (valid(token)) loading.value = false }
}
async function decide(item, accept) {
  if (props.disabled || loading.value || busy.value || !snapshot.value || (accept && !canAccept(item))) return
  const token = generation, project = props.projectId
  const payload = { revision:snapshot.value.revision, version:item.version, targetVersion:item.targetVersion || 0, accept }
  busy.value = true;emit('busy',true);error.value = '';notice.value = ''
  let refresh = false
  try {
    await memoryApi.decideCandidate(project,item.id,payload)
    if (!valid(token)) return
    notice.value = accept ? '候选已确认保存，后续任务可按需取回。' : '候选已拒绝，不会作为有效记忆使用。'
    refresh = true;emit('changed')
  } catch (failure) {
    if (!valid(token)) return
    if (failure.response?.status === 409) {
      error.value = '候选或目标记忆版本已变化。已刷新候选，请核对内容与目标版本后再决定；不会自动重复提交。'
      refresh = true;emit('changed')
    } else error.value = '处理结果尚未确认。请刷新候选核对状态后再决定；不会自动重复提交。'
  } finally {
    if (valid(token)) {busy.value = false;emit('busy',false);if(refresh)await reload(false)}
  }
}
watch([() => props.projectId,owner], () => {generation++;snapshot.value=null;busy.value=false;emit('busy',false);error.value='';notice.value='';reload()}, {immediate:true,flush:'sync'})
onBeforeUnmount(() => {disposed=true;generation++;emit('busy',false)})
</script>
<template>
<section class="memory-candidates" aria-labelledby="memory-candidates-title" data-testid="memory-candidates">
 <div class="candidates-heading"><h3 id="memory-candidates-title">待审阅的记忆候选</h3><button type="button" class="quiet-button" :disabled="disabled||loading||busy" @click="reload()" data-testid="candidates-refresh">刷新候选</button></div>
 <p class="candidates-note">候选不会立即参与召回。核对来源与内容后再确认；模型反思、工具观察只能拒绝，或由你核实后在下方手动保存。</p>
 <p v-if="loading" role="status">正在加载候选…</p><p v-if="error" role="alert" class="candidates-error">{{error}}</p><p v-if="notice" role="status" class="candidates-notice">{{notice}}</p>
 <p v-if="snapshot&&!items.length" class="candidates-note" data-testid="candidates-empty">暂无待审阅候选。</p>
 <article v-for="item in items" :key="item.id" class="candidate-card" :data-testid="'candidate-'+item.id">
  <h4>{{item.key}} <small>{{kinds[item.kind]||item.kind}} · 候选 v{{item.version}}</small></h4>
  <p class="candidate-content">{{item.content}}</p>
  <dl><dt>来源</dt><dd>{{sources[item.sourceType]||'未知来源（不能确认）'}}</dd><dt>不确定性</dt><dd>{{uncertainty(item.uncertainty)}}</dd><dt>提出时间</dt><dd>{{time(item.createdAt)}}</dd><dt>过期时间</dt><dd>{{time(item.expiresAt)}}{{expired(item)?' · 已过期或时间无效':''}}</dd><template v-if="item.sourceSessionId"><dt>来源会话 / 轮次</dt><dd>{{item.sourceSessionId}} / {{item.sourceTurnId||'未提供'}}</dd></template><dt>保存方式</dt><dd>{{item.targetId?'更正已有条目 · 目标版本 v'+item.targetVersion:'新增条目'}}</dd></dl>
  <p v-if="item.sourceType!=='USER_EXPLICIT'" class="candidates-note">这不是已确认的用户事实。请独立核实后手动保存。</p><p v-else-if="!enabled" class="candidates-note">项目记忆已关闭，启用后才可确认保存。</p>
  <footer><button type="button" class="quiet-button" :disabled="disabled||loading||busy" @click="decide(item,false)" :data-testid="'candidate-reject-'+item.id">拒绝候选</button><button type="button" class="coding-primary" :disabled="disabled||loading||busy||!canAccept(item)" @click="decide(item,true)" :data-testid="'candidate-accept-'+item.id">{{item.targetId?'确认更正':'确认保存'}}</button></footer>
 </article>
</section>
</template>
<style scoped>
.memory-candidates{margin-top:22px;border-top:1px solid #e4eaf2;padding-top:14px}.candidates-heading{display:flex;justify-content:space-between;align-items:center;gap:12px}.memory-candidates h3{font-size:14px;margin:0}.candidates-note{font-size:12px;line-height:1.7;color:#64748b}.candidate-card{border:1px solid #dce5ef;border-radius:10px;padding:14px;margin:12px 0;background:#fbfcff}.candidate-card h4{font-size:13px;margin:0;overflow-wrap:anywhere}.candidate-card small{font-size:11px;font-weight:400;color:#64748b;margin-left:8px}.candidate-content{white-space:pre-wrap;overflow-wrap:anywhere;font-size:13px;line-height:1.7}.candidate-card dl{display:grid;grid-template-columns:auto 1fr;gap:6px 12px;font-size:11px;color:#64748b}.candidate-card dt{font-weight:600}.candidate-card dd{margin:0;overflow-wrap:anywhere}.candidate-card footer{display:flex;justify-content:flex-end;gap:8px;margin-top:14px}.candidates-error,.candidates-notice{font-size:12px;line-height:1.7;padding:10px;border-radius:8px}.candidates-error{background:#fff1ee;color:#a63e37}.candidates-notice{background:#edf9f2;color:#276947}
</style>
