<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api, errorMessage } from '../workspace-api'
import { auth } from '../auth'
import WorkspaceLayout from '../components/WorkspaceLayout.vue'

const skills = ref([]), selected = ref(null), draft = ref(null), busy = ref(''), loading = ref(false)
const error = ref(''), notice = ref(''), listError = ref(''), conflict = ref(false), deleteTarget = ref(null)
const builtins = computed(() => skills.value.filter(skill => skill.builtin))
const personal = computed(() => skills.value.filter(skill => !skill.builtin))
const readonly = computed(() => !!selected.value?.builtin)
const owner = () => auth.user?.id || auth.user?.username || ''
let disposed = false, listEpoch = 0, readEpoch = 0, listController, readController
const fields = skill => ({ name: skill.name || '', description: skill.description || '', instructions: skill.instructions || '', useKnowledge: !!skill.useKnowledge, exportPdf: !!skill.exportPdf, enabled: skill.enabled !== false })
function dateTime(value) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—' }
async function refresh() {
  const current = ++listEpoch, account = owner()
  listController?.abort(); listController = new AbortController()
  try {
    const { data } = await api.get('/ai/skills', { signal: listController.signal, timeout: 20000 })
    if (!disposed && current === listEpoch && account === owner()) { skills.value = Array.isArray(data) ? data : []; listError.value = '' }
  } catch (e) {
    if (!disposed && current === listEpoch && account === owner() && e.code !== 'ERR_CANCELED') listError.value = errorMessage(e)
  }
}
function newSkill(source) {
  readEpoch++; readController?.abort(); selected.value = null; loading.value = false; conflict.value = false; error.value = ''; notice.value = ''; deleteTarget.value = null
  draft.value = source ? { ...fields(source), name: `${source.name}（副本）`.slice(0, 60), enabled: true } : { name: '', description: '', instructions: '', useKnowledge: true, exportPdf: true, enabled: true }
}
async function open(skill) {
  if (busy.value) return
  const id = skill.id, current = ++readEpoch, account = owner()
  readController?.abort(); readController = new AbortController(); loading.value = true; error.value = ''; notice.value = ''; deleteTarget.value = null
  // Clear the previous selection before fetching another skill to prevent stale edits.
  selected.value = null; draft.value = null; conflict.value = false
  try {
    const { data } = await api.get(`/ai/skills/${encodeURIComponent(id)}`, { signal: readController.signal, timeout: 20000 })
    if (disposed || current !== readEpoch || account !== owner()) return
    if (data.id !== id) throw new Error('Skill mismatch')
    selected.value = data; draft.value = fields(data)
  } catch (e) {
    if (!disposed && current === readEpoch && account === owner() && e.code !== 'ERR_CANCELED') error.value = [403,404].includes(e.response?.status) ? '技能不存在或当前账号无权访问。' : errorMessage(e)
  } finally { if (!disposed && current === readEpoch) loading.value = false }
}
async function reloadLatest() {
  if (!selected.value || busy.value) return
  // Unlike switching selection, a failed conflict reload must retain the draft.
  const id = selected.value.id, current = ++readEpoch, account = owner()
  readController?.abort(); readController = new AbortController(); loading.value = true; error.value = ''
  try {
    const { data } = await api.get(`/ai/skills/${encodeURIComponent(id)}`, { signal: readController.signal, timeout: 20000 })
    if (disposed || current !== readEpoch || account !== owner()) return
    if (data.id !== id) throw new Error('Skill mismatch')
    selected.value = data; draft.value = fields(data); conflict.value = false; notice.value = '已读取最新版本，可重新编辑后保存。'
  } catch (e) { if (!disposed && current === readEpoch && account === owner() && e.code !== 'ERR_CANCELED') error.value = errorMessage(e) }
  finally { if (!disposed && current === readEpoch) loading.value = false }
}
async function save() {
  if (busy.value || readonly.value || !draft.value || conflict.value) return
  busy.value = 'save'; error.value = ''; notice.value = ''
  const account = owner(), current = readEpoch, id = selected.value?.id
  const body = { ...draft.value, name: draft.value.name.trim(), description: draft.value.description.trim(), instructions: draft.value.instructions.trim() }
  if (!body.name || !body.instructions) { error.value = '请填写技能名称和执行指引。'; busy.value = ''; return }
  try {
    const { data } = id ? await api.put(`/ai/skills/${encodeURIComponent(id)}`, { ...body, version: selected.value.version }) : await api.post('/ai/skills', { name: body.name, description: body.description, instructions: body.instructions, useKnowledge: body.useKnowledge, exportPdf: body.exportPdf })
    if (disposed || account !== owner() || current !== readEpoch) return
    selected.value = data; draft.value = fields(data); conflict.value = false; notice.value = '技能已保存，可以用于新任务。已有任务继续使用创建时的技能版本。'
    await refresh()
  } catch (e) {
    if (disposed || account !== owner() || current !== readEpoch) return
    conflict.value = e.response?.status === 409
    error.value = conflict.value ? '技能已被其他操作更新。当前草稿已保留，请读取最新版本后重新编辑，避免覆盖他人的修改。' : errorMessage(e)
  } finally { if (!disposed && account === owner()) busy.value = '' }
}
async function toggle(skill) {
  if (busy.value || skill.builtin) return
  busy.value = `toggle:${skill.id}`; error.value = ''; notice.value = ''
  const account = owner()
  try {
    const { data } = await api.put(`/ai/skills/${encodeURIComponent(skill.id)}`, { ...fields(skill), version: skill.version, enabled: !skill.enabled })
    if (disposed || account !== owner()) return
    notice.value = skill.enabled ? '技能已停用，不再供新任务选择。已有任务不受影响。' : '技能已启用。'
    if (selected.value?.id === skill.id) {
      // Never advance a stale draft to a new version silently.
      if (selected.value.version === skill.version) { selected.value = data; if (draft.value) draft.value.enabled = data.enabled }
      else { conflict.value = true; error.value = '技能状态已更新，编辑器仍保留之前的草稿。请读取最新版本后再保存。' }
    }
    await refresh()
  } catch (e) {
    if (!disposed && account === owner()) { error.value = e.response?.status === 409 ? '技能版本已更新，请刷新技能列表后再操作。' : errorMessage(e); await refresh() }
  } finally { if (!disposed && account === owner()) busy.value = '' }
}
async function remove() {
  const skill = deleteTarget.value
  if (!skill || busy.value || skill.builtin) return
  busy.value = 'delete'; error.value = ''; notice.value = ''; const account = owner()
  try {
    await api.delete(`/ai/skills/${encodeURIComponent(skill.id)}`, { params: { version: skill.version } })
    if (disposed || account !== owner()) return
    if (selected.value?.id === skill.id) { selected.value = null; draft.value = null; conflict.value = false }
    deleteTarget.value = null; notice.value = '技能已删除。已有任务中的技能快照继续保留。'; await refresh()
  } catch (e) {
    if (!disposed && account === owner()) { error.value = e.response?.status === 409 ? '技能版本已更新，删除未执行。请刷新后重新选择。' : errorMessage(e); deleteTarget.value = null; await refresh() }
  } finally { if (!disposed && account === owner()) busy.value = '' }
}
watch(() => owner(), () => {
  listEpoch++; readEpoch++; listController?.abort(); readController?.abort()
  loading.value = false; busy.value = ''; skills.value = []; selected.value = null; draft.value = null; deleteTarget.value = null; error.value = ''; notice.value = ''; listError.value = ''; conflict.value = false
  if (owner()) refresh()
})
onMounted(refresh)
onBeforeUnmount(() => { disposed = true; listEpoch++; readEpoch++; listController?.abort(); readController?.abort() })
</script>

<template>
  <WorkspaceLayout title="技能中心" description="把你的出行规划方法保存为技能，为每次任务选择合适的指引和可用能力。">
    <div class="row skills-toolbar"><button :disabled="!!busy" @click="newSkill()">新建技能</button><button class="secondary" :disabled="!!busy" @click="refresh">刷新技能列表</button><RouterLink class="button" to="/tasks">前往任务工作台</RouterLink></div>
    <div v-if="error" class="error" role="alert">{{error}}</div><div v-if="notice" class="notice" role="status">{{notice}}</div><div v-if="listError" class="error" role="alert">{{listError}}</div>
    <section v-if="deleteTarget" class="panel delete-confirm" aria-label="删除技能确认"><h2>删除“{{deleteTarget.name}}”？</h2><p>删除后无法用于新任务，已有任务的技能记录不受影响。</p><div class="row"><button class="danger" :disabled="!!busy" @click="remove">确认删除</button><button class="secondary" :disabled="!!busy" @click="deleteTarget = null">保留技能</button></div></section>
    <div class="skills-grid">
      <div>
        <section class="panel" aria-label="内置技能"><h2>内置技能</h2><p class="muted">可直接使用，也可复制后调整为自己的方法。</p><article v-for="skill in builtins" :key="skill.id" class="skill-card" :data-testid="`skill-${skill.id}`"><div class="row"><h3>{{skill.name}}</h3><span class="badge">内置 · v{{skill.version}}</span></div><p>{{skill.description}}</p><div class="skill-capabilities"><span>天气与地点</span><span v-if="skill.useKnowledge">允许知识检索</span><span v-if="skill.exportPdf">允许 PDF 导出</span></div><div class="row"><RouterLink v-if="skill.enabled" class="button" :to="{path:'/tasks',query:{skill:skill.id}}">使用技能</RouterLink><button class="secondary" :disabled="!!busy" @click="open(skill)">查看技能</button><button class="secondary" :disabled="!!busy" @click="newSkill(skill)">复制技能</button></div></article><p v-if="!builtins.length && !listError" class="muted">暂无内置技能。</p></section>
        <section class="panel" aria-label="我的技能"><h2>我的技能</h2><p class="muted">仅当前账号可管理和使用。停用或修改不会改变已创建任务。</p><article v-for="skill in personal" :key="skill.id" class="skill-card" :data-testid="`skill-${skill.id}`"><div class="row"><h3>{{skill.name}}</h3><span class="badge" :class="{failed:!skill.enabled}">{{skill.enabled ? '已启用' : '已停用'}} · v{{skill.version}}</span></div><p>{{skill.description}}</p><div class="skill-capabilities"><span>天气与地点</span><span v-if="skill.useKnowledge">允许知识检索</span><span v-if="skill.exportPdf">允许 PDF 导出</span></div><div class="row"><RouterLink v-if="skill.enabled" class="button" :to="{path:'/tasks',query:{skill:skill.id}}">使用技能</RouterLink><button class="secondary" :disabled="!!busy" @click="open(skill)">编辑技能</button><button class="secondary" :disabled="!!busy" @click="toggle(skill)">{{skill.enabled ? '停用技能' : '启用技能'}}</button><button class="danger" :disabled="!!busy" @click="deleteTarget = skill">删除技能</button></div></article><p v-if="!personal.length" class="muted skill-empty">尚未创建技能。点击“新建技能”或复制一个内置技能开始。</p></section>
      </div>
      <section class="panel skill-editor" aria-label="技能编辑器">
        <p v-if="loading" role="status">正在读取技能…</p>
        <template v-if="draft">
          <div class="row"><h2>{{readonly ? '查看内置技能' : selected ? '编辑我的技能' : '新建我的技能'}}</h2><span v-if="selected" class="badge">版本 {{selected.version}}</span></div>
          <p v-if="readonly" class="notice">内置技能仅可查看。复制后可以编辑并保存为自己的技能。</p>
          <div v-if="conflict" class="notice" role="status"><p>重新读取会用服务器最新版本替换当前编辑内容，请先保留需要的文字。</p><button type="button" class="secondary" :disabled="loading || !!busy" @click="reloadLatest">重新读取最新版本</button></div>
          <form @submit.prevent="save">
            <fieldset :disabled="readonly || !!busy || loading">
              <label>技能名称<input v-model="draft.name" required maxlength="60" placeholder="例如：雨天轻松出行"></label>
              <label>技能简介<textarea v-model="draft.description" maxlength="300" placeholder="说明适用场景和预期结果" class="short-text"></textarea></label>
              <label>执行指引<textarea v-model="draft.instructions" required maxlength="4000" rows="9" placeholder="例如：优先选择室内活动；给出雨天备选；控制步行强度；费用必须在预算以内。"></textarea></label>
              <label class="check"><input v-model="draft.useKnowledge" type="checkbox">允许检索我的知识库</label>
              <label class="check"><input v-model="draft.exportPdf" type="checkbox">允许导出 PDF</label>
              <label v-if="selected && !readonly" class="check"><input v-model="draft.enabled" type="checkbox">启用技能</label>
            </fieldset>
            <p class="muted skill-hint">天气与地图地点始终作为规划依据。勾选的能力可用于计划，实际执行步骤会在任务开始前展示，待你确认。</p>
            <button v-if="!readonly" type="submit" :disabled="!!busy || loading || conflict">{{busy === 'save' ? '正在保存…' : '保存技能'}}</button>
            <button v-else type="button" :disabled="!!busy" @click="newSkill(selected)">复制为我的技能</button>
            <p v-if="selected" class="muted skill-hint">更新于 {{dateTime(selected.updatedAt)}}。保存新版本不会改变已有任务中的技能快照。</p>
          </form>
        </template>
        <div v-else-if="!loading" class="editor-empty"><span class="badge">可复用的工作方法</span><h2>把偏好变成执行指引</h2><p>先选择一个内置技能查看，或新建自己的技能。保存成功后即可在任务工作台使用。</p></div>
      </section>
    </div>
  </WorkspaceLayout>
</template>

<style scoped>
.skills-toolbar{margin-bottom:22px}.skills-grid{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1.1fr);gap:22px;align-items:start}.skill-card{border-top:1px solid #e6eeea;padding:18px 0;overflow-wrap:anywhere}.skill-card:first-of-type{margin-top:16px}.skill-card:last-child{padding-bottom:0}.skill-card h3{margin:4px 0;font-size:17px}.skill-card p{font-size:14px;margin:10px 0}.skill-card .row{gap:8px}.skill-card button,.skill-card .button{font-size:12px;padding:8px 10px}.skill-capabilities{display:flex;flex-wrap:wrap;gap:6px;margin:12px 0 16px}.skill-capabilities span{font-size:11px;color:#57706c;padding:4px 8px;background:#f2f6f4;border-radius:5px}.skill-empty{margin-top:18px}.skill-editor{overflow-wrap:anywhere}.skill-editor fieldset{border:0;min-width:0}.skill-editor .short-text{min-height:76px}.skill-editor .check{display:flex;align-items:center;gap:10px;margin:16px 0;font-weight:400}.skill-editor .check input{display:inline-block;width:17px;height:17px;margin:0;padding:0;accent-color:#196f69;flex-shrink:0}.skill-hint{margin:16px 0}.editor-empty{padding:28px 0}.editor-empty h2{margin-top:20px;font-size:23px}.delete-confirm .row{margin-top:16px}.skill-editor h2{margin:0 0 15px}@media(max-width:850px){.skills-grid{grid-template-columns:1fr}.skill-editor{grid-row:1}.skills-toolbar{gap:8px}}
</style>
