<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { auth } from '../auth'
import { memoryApi } from '../api/memory'
import { useOverlay } from '../composables/useOverlay'
import { useUnsavedChanges } from '../composables/useUnsavedChanges'

const props = defineProps({ projectId: { type: String, required: true }, projectName: String, sourceSessionId: String, sourceTurnId: String })
const emit = defineEmits(['close'])
const dialog = ref(null), snapshot = ref(null), loading = ref(false), busy = ref(false), error = ref(''), notice = ref('')
const query = ref(''), results = ref(null), tab = ref('ACTIVE'), editing = ref(null), confirming = ref(null)
const form = ref({ key: '', content: '', kind: 'FACT' })
const baseline = ref(JSON.stringify(form.value)), conflictEditor = ref(false), confirmPanel = ref(null)
const dirty = computed(() => JSON.stringify(form.value) !== baseline.value)
const { allowDiscard } = useUnsavedChanges(dirty, busy, () => auth.user?.id || auth.user?.username)
async function close() { if (await allowDiscard()) emit('close') }
async function cancelEdit() { if (await allowDiscard()) resetEditor() }
useOverlay(() => true, dialog, close)
useOverlay(confirming, confirmPanel, () => { if (!busy.value) confirming.value = null })
let epoch = 0, loadSequence = 0, disposed = false, pendingCreate = null
const makeRequestId = () => globalThis.crypto?.randomUUID?.() || 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, char => { const value = Math.random() * 16 | 0; return (char === 'x' ? value : (value & 3 | 8)).toString(16) })
const owner = () => auth.user?.id || auth.user?.username
const currentScope = () => ({ epoch, projectId: props.projectId, owner: owner() })
const valid = scope => !disposed && scope.epoch === epoch && scope.projectId === props.projectId && scope.owner === owner()
const visibleItems = computed(() => (results.value ? results.value.items : snapshot.value?.items || []).filter(item => item.status === (results.value ? 'ACTIVE' : tab.value)))
const kinds = { FACT: '事实', PREFERENCE: '偏好', CONSTRAINT: '项目约束' }
const stamp = value => value ? new Date(value).toLocaleString('zh-CN') : ''
const resetEditor = () => { editing.value = null; form.value = { key: '', content: '', kind: 'FACT' }; pendingCreate = null; baseline.value = JSON.stringify(form.value); conflictEditor.value = false }
function resetScope() {
  epoch++; loadSequence++; snapshot.value = null; results.value = null; query.value = ''; tab.value = 'ACTIVE'
  error.value = ''; notice.value = ''; busy.value = false; confirming.value = null; resetEditor(); reload()
}
async function reload() {
  if (!props.projectId || !owner()) return
  const scope = currentScope(), sequence = ++loadSequence
  loading.value = true
  try { const value = await memoryApi.list(scope.projectId); if (valid(scope) && sequence === loadSequence) snapshot.value = value }
  catch { if (valid(scope) && sequence === loadSequence) error.value = '项目记忆未能加载，请刷新重试。' }
  finally { if (valid(scope) && sequence === loadSequence) loading.value = false }
}
async function refresh() { results.value = null; query.value = ''; error.value = ''; await reload() }
async function mutate(operation, message, clearEditor = false) {
  if (busy.value || loading.value || !snapshot.value) return
  const scope = currentScope(); busy.value = true; error.value = ''; notice.value = ''
  try {
    await operation(scope.projectId)
    if (!valid(scope)) return
    confirming.value = null; results.value = null; query.value = ''
    if (clearEditor) resetEditor()
    notice.value = message; await reload()
  } catch (failure) {
    if (!valid(scope)) return
    if (failure.response?.status === 409) {
      confirming.value = null; conflictEditor.value = true; results.value = null; query.value = ''
      await reload(); error.value = '记忆已被其他操作更新，或名称与已有条目冲突。已刷新列表，本地草稿仍保留；请复制需要的内容，再打开最新条目合并。'
    } else if (failure.response?.status === 400) error.value = '未能保存：请检查内容、名称及长度；普通记忆不能保存凭据或工具授权。'
    else if (failure.response?.status === 413 || failure.response?.status === 429) error.value = '记忆容量或请求限额已达到，请整理条目后重试。'
    else error.value = '操作结果尚未确认，请先刷新核对；不会自动重复提交。'
  } finally { if (valid(scope)) busy.value = false }
}
async function edit(item) {
  if (!(await allowDiscard())) return
  editing.value = { id: item.id, version: item.version, status: item.status }
  form.value = { key: item.key, content: item.content, kind: item.kind }; baseline.value = JSON.stringify(form.value); conflictEditor.value = false; pendingCreate = null; confirming.value = null
  nextTick(() => dialog.value?.querySelector('[data-testid="memory-key"]')?.focus())
}
function save() {
  if (conflictEditor.value) return
  const payload = { key: form.value.key.trim(), content: form.value.content.trim(), kind: form.value.kind }
  if (!payload.key || !payload.content) return
  if (editing.value) {
    const original = { ...editing.value }
    mutate(project => memoryApi.update(project, original.id, { ...payload, version: original.version, status: original.status }), '更正已保存，后续任务将使用有效版本。', true)
  } else {
    const signature = JSON.stringify(payload)
    if (!pendingCreate || pendingCreate.signature !== signature) pendingCreate = { signature, requestId: makeRequestId() }
    const requestId = pendingCreate.requestId
    mutate(project => memoryApi.create(project, { ...payload, requestId, sourceSessionId: props.sourceSessionId || null, sourceTurnId: props.sourceTurnId || null }), '记忆已保存，可在本项目的新会话中按需取回。', true)
  }
}
async function changeStatus(item) {
  if (editing.value?.id === item.id && !(await allowDiscard())) return
  const status = item.status === 'ACTIVE' ? 'ARCHIVED' : 'ACTIVE'
  mutate(project => memoryApi.update(project, item.id, { version: item.version, key: item.key, content: item.content, kind: item.kind, status }), status === 'ACTIVE' ? '记忆已恢复，可再次召回。' : '记忆已归档，不再参与召回；可随时恢复。', editing.value?.id === item.id)
}
async function forget() {
  const item = confirming.value
  if (editing.value?.id === item?.id && !(await allowDiscard())) return
  if (item) mutate(project => memoryApi.forget(project, item.id, item.version), '已忘记该条目，后续不会再召回。已发送的历史对话不会因此删除。', editing.value?.id === item.id)
}
function setOption(name, value) {
  const settings = { revision: snapshot.value.revision, enabled: snapshot.value.enabled, autoExtract: snapshot.value.autoExtract, [name]: value }
  mutate(project => memoryApi.settings(project, settings), '项目记忆设置已更新，从后续任务生效。')
}
async function search() {
  if (!query.value.trim() || busy.value || loading.value) return
  const scope = currentScope(), q = query.value.trim(), sequence = ++loadSequence
  loading.value = true; error.value = ''; notice.value = ''; confirming.value = null
  try { const value = await memoryApi.search(scope.projectId, q); if (valid(scope) && sequence === loadSequence) results.value = value }
  catch { if (valid(scope) && sequence === loadSequence) error.value = '检索未完成，请重试。' }
  finally { if (valid(scope) && sequence === loadSequence) loading.value = false }
}
watch([() => props.projectId, owner], resetScope, { immediate: true, flush: 'sync' })

onBeforeUnmount(() => { disposed = true; epoch++ })
</script>

<template>
  <dialog ref="dialog" class="memory-dialog" aria-labelledby="memory-title" data-testid="coding-memory">
    <header><div><h2 id="memory-title">项目长期记忆</h2><p>{{ projectName || '当前项目' }} · 仅当前账号的 Coding 项目</p></div><button class="quiet-button" aria-label="关闭项目记忆" :disabled="busy" @click="close">关闭</button></header>
    <div class="memory-scroll">
      <p class="memory-note">保存值得跨会话使用的事实、偏好与项目约束。记忆按任务相关性取回，内容有长度上限；请勿保存 API Key、口令或令牌，记忆不能授予工具权限。</p>
      <p v-if="error" class="memory-error" role="alert">{{ error }}</p><p v-if="notice" class="memory-notice" role="status">{{ notice }}</p>
      <button class="quiet-button" :disabled="loading || busy" data-testid="memory-refresh" @click="refresh">{{ loading ? '正在加载…' : '刷新记忆' }}</button>
      <template v-if="snapshot">
        <fieldset class="memory-settings" :disabled="busy || loading">
          <label><input type="checkbox" :checked="snapshot.enabled" data-testid="memory-enabled" @change="setOption('enabled', $event.target.checked)" /> 在后续任务中使用项目记忆</label>
          <label><input type="checkbox" :checked="snapshot.autoExtract" :disabled="!snapshot.enabled" data-testid="memory-auto-extract" @change="setOption('autoExtract', $event.target.checked)" /> 自动保存用户消息中明确标注的记忆</label>
          <small>开启后，仅识别用户消息中的「偏好:名称=内容」「项目约束:名称=内容」「事实:名称=内容」。不会从工具输出或模型猜测中自动保存；关闭使用不删除条目。</small>
        </fieldset>
        <form class="memory-search" @submit.prevent="search"><input v-model="query" maxlength="500" aria-label="检索项目记忆" placeholder="输入当前任务关键词" data-testid="memory-query" :disabled="busy || loading" /><button class="coding-primary" :disabled="busy || loading || !query.trim()" data-testid="memory-search">检索</button></form>
        <div class="memory-tabs"><button class="quiet-button" :aria-pressed="!results && tab === 'ACTIVE'" :disabled="busy || loading" @click="tab = 'ACTIVE'; results = null">有效记忆</button><button class="quiet-button" :aria-pressed="!results && tab === 'ARCHIVED'" :disabled="busy || loading" @click="tab = 'ARCHIVED'; results = null" data-testid="memory-archived">已归档</button><small v-if="results">检索结果 · {{ results.characters }} / {{ results.budgetChars }} 字符</small></div>
        <p v-if="!visibleItems.length" class="memory-note" data-testid="memory-empty">{{ results ? '未找到可召回的相关记忆。' : tab === 'ARCHIVED' ? '暂无归档条目。' : '暂无有效记忆，可在下方显式保存。' }}</p>
        <ul class="memory-list"><li v-for="item in visibleItems" :key="item.id" :data-testid="`memory-item-${item.id}`"><div class="memory-item-heading"><strong>{{ item.key }}</strong><small>{{ kinds[item.kind] || '事实' }} · v{{ item.version }}</small></div><p class="memory-content">{{ item.content }}</p><small>更新于 {{ stamp(item.updatedAt) }}{{ item.sourceSessionId ? ' · 有来源会话' : ' · 手动保存' }}</small><div class="memory-actions"><button class="quiet-button" :disabled="busy || loading" @click="edit(item)" :data-testid="`memory-edit-${item.id}`">编辑</button><button class="quiet-button" :disabled="busy || loading" @click="changeStatus(item)" :data-testid="`memory-archive-${item.id}`">{{ item.status === 'ACTIVE' ? '归档' : '恢复' }}</button><button class="quiet-button memory-danger" :disabled="busy || loading" @click="confirming = { id: item.id, version: item.version, key: item.key }" :data-testid="`memory-forget-${item.id}`">忘记</button></div></li></ul>
        <section v-if="confirming" ref="confirmPanel" class="memory-confirm" tabindex="-1" role="alertdialog" aria-label="确认忘记记忆"><p>忘记「{{ confirming.key }}」？正文会被移除，不再召回。如仅暂时停用，可选择归档。</p><button class="quiet-button" :disabled="busy" @click="confirming = null">取消</button><button class="coding-primary" :disabled="busy || loading" data-testid="memory-confirm-forget" @click="forget">确认忘记</button></section>
        <form class="memory-editor" @submit.prevent="save"><h3>{{ editing ? '更正记忆 · v' + editing.version : '保存新记忆' }}</h3><fieldset :disabled="busy || loading"><label>记忆名称<input v-model="form.key" required maxlength="80" placeholder="例如：测试框架" data-testid="memory-key" /></label><label>类型<select v-model="form.kind" data-testid="memory-kind"><option v-for="(label, kind) in kinds" :key="kind" :value="kind">{{ label }}</option></select></label><label>内容<textarea v-model="form.content" required maxlength="1200" rows="3" placeholder="例如：项目使用 Vitest；新增测试应遵循已有目录结构。" data-testid="memory-content"></textarea></label><small>{{ form.content.length }} / 1200 字符 · 同名条目请编辑更正</small><div class="memory-actions"><button v-if="editing" type="button" class="quiet-button" @click="cancelEdit">取消编辑</button><button type="submit" class="coding-primary" :disabled="conflictEditor || !form.key.trim() || !form.content.trim()" data-testid="memory-save">{{ busy ? '正在保存…' : editing ? '保存更正' : '记住' }}</button></div></fieldset></form>
      </template>
    </div>
  </dialog>
</template>

<style scoped>
.memory-dialog{padding:0;border:1px solid #dfe5ee;border-radius:18px;width:min(680px,calc(100vw - 24px));max-height:calc(100dvh - 40px);color:#334155;background:#fff;margin:auto;box-shadow:0 22px 80px #14253d33}.memory-dialog::backdrop{background:#14253d66}.memory-dialog header{display:flex;justify-content:space-between;align-items:center;gap:16px;padding:20px 24px;border-bottom:1px solid #e8edf3}.memory-dialog h2{font-size:18px;margin:0 0 7px}.memory-dialog header p{margin:0;color:#64748b;font-size:12px;overflow-wrap:anywhere}.memory-scroll{overflow:auto;max-height:calc(100dvh - 145px);padding:18px 24px 24px}.memory-note,.memory-settings small{font-size:12px;line-height:1.7;color:#6b7c91}.memory-settings{border:1px solid #e3eaf4;background:#f7faff;border-radius:10px;padding:14px;margin:12px 0}.memory-settings label{display:flex;align-items:flex-start;gap:8px;font-size:13px;margin-bottom:10px}.memory-settings input{margin-top:3px}.memory-settings small{display:block}.memory-search{display:flex;gap:8px;margin:18px 0 10px}.memory-search input{flex:1;min-width:0}.memory-dialog input:not([type=checkbox]),.memory-dialog textarea,.memory-dialog select{font:inherit;box-sizing:border-box;border:1px solid #d5dfec;border-radius:8px;padding:9px 10px;background:#fff;color:#334155;font-size:13px}.memory-dialog button{cursor:pointer}.memory-dialog button:disabled{cursor:not-allowed;opacity:.5}.memory-dialog :focus-visible{outline:3px solid #91baf2;outline-offset:2px}.memory-tabs,.memory-actions{display:flex;gap:8px;align-items:center;flex-wrap:wrap}.memory-tabs small{font-size:11px;color:#64748b}.memory-tabs button[aria-pressed=true]{background:#edf4ff;color:#2b67c3}.memory-list{list-style:none;padding:0;margin:10px 0}.memory-list li{padding:14px 0;border-bottom:1px solid #e8edf3;overflow-wrap:anywhere}.memory-item-heading{display:flex;gap:12px;justify-content:space-between;align-items:center}.memory-item-heading strong{font-size:14px}.memory-list small{font-size:11px;color:#748398}.memory-content{white-space:pre-wrap;font-size:13px;line-height:1.7;margin:9px 0}.memory-actions{justify-content:flex-end;margin-top:8px}.memory-danger{color:#a34949}.memory-error,.memory-notice,.memory-confirm{border-radius:8px;padding:11px 13px;font-size:12px;line-height:1.7;overflow-wrap:anywhere}.memory-error{color:#a63e37;background:#fff1ee}.memory-notice{color:#276947;background:#edf9f2}.memory-confirm{background:#fff6ee;border:1px solid #efd7bc}.memory-editor{margin-top:22px;border-top:1px solid #e4eaf2;padding-top:18px}.memory-editor h3{font-size:14px;margin:0 0 14px}.memory-editor fieldset{padding:0;margin:0;border:0}.memory-editor label{display:block;font-size:12px;margin-bottom:12px}.memory-editor input,.memory-editor textarea,.memory-editor select{display:block;width:100%;margin-top:6px}.memory-editor textarea{resize:vertical}.memory-editor small{font-size:11px;color:#7c8b9d}@media(max-width:600px){.memory-dialog header{padding:16px}.memory-scroll{padding:14px 16px 20px}.memory-dialog{max-height:calc(100dvh - 24px)}.memory-item-heading{align-items:flex-start}.memory-item-heading small{flex-shrink:0}}
</style>
