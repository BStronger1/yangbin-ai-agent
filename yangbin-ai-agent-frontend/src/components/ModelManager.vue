<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import StudioIcon from './StudioIcon.vue'
import { modelApi } from '../api/models'
import { auth } from '../auth'
const props = defineProps({ catalog: { type: Object, required: true } })
const emit = defineEmits(['close', 'changed', 'selected'])
const dialog = ref(null), activeId = ref(''), name = ref(''), provider = ref('openai'), baseUrl = ref(''), model = ref(''), apiKey = ref('')
const saving = ref(false), error = ref(''), message = ref(''), confirmDelete = ref(false)
const insecureConnection = location.protocol !== 'https:' && !['localhost', '127.0.0.1', '[::1]'].includes(location.hostname)
const owner = auth.user?.id || auth.user?.username
let disposed = false, previousFocus = null
const existing = computed(() => props.catalog.profiles?.find(item => item.id === activeId.value))
const providerInfo = computed(() => props.catalog.providers?.find(item => item.id === provider.value))
const isNew = computed(() => !activeId.value)
const valid = computed(() => name.value.trim() && provider.value && baseUrl.value.trim() && model.value.trim() && (existing.value?.hasKey || apiKey.value.trim()))
function preset() { const item = providerInfo.value; if (!item) return; baseUrl.value = item.defaultBaseUrl || ''; model.value = item.suggestedModels?.[0] || ''; if (!name.value || isNew.value) name.value = item.name || ''; apiKey.value = ''; error.value = ''; message.value = '' }
function edit(profile) { activeId.value = profile?.id || ''; apiKey.value = ''; error.value = ''; message.value = ''; confirmDelete.value = false; name.value = profile?.name || ''; provider.value = profile?.provider || props.catalog.providers?.[0]?.id || 'openai'; baseUrl.value = profile?.baseUrl || ''; model.value = profile?.model || ''; if (!profile) preset() }
function alive() { return !disposed && owner === (auth.user?.id || auth.user?.username) }
function close() { if (saving.value) return; apiKey.value = ''; emit('close') }
async function save() {
  if (!valid.value || saving.value) return
  saving.value = true; error.value = ''; message.value = ''
  const payload = { name: name.value.trim(), provider: provider.value, baseUrl: baseUrl.value.trim(), model: model.value.trim(), apiKey: apiKey.value }
  try { const result = activeId.value ? await modelApi.update(activeId.value, payload) : await modelApi.create(payload); if (!alive()) return; apiKey.value = ''; activeId.value = result.id; emit('changed', result.id); message.value = '已保存。可以在输入框中选择此模型。' }
  catch { if (alive()) error.value = '保存未完成，请检查地址、模型 ID 和密钥后重试。' }
  finally { payload.apiKey = ''; apiKey.value = ''; saving.value = false }
}
async function remove() {
  if (!activeId.value || saving.value) return
  if (!confirmDelete.value) { confirmDelete.value = true; return }
  saving.value = true; error.value = ''
  try { await modelApi.remove(activeId.value); if (!alive()) return; emit('changed'); edit(null); message.value = '模型配置已删除，历史任务记录保留。' }
  catch { if (alive()) error.value = '删除未完成，请稍后重试。' } finally { saving.value = false }
}
async function setDefault(id) {
  if (saving.value) return
  saving.value = true; error.value = ''
  try { await modelApi.setDefault(id); if (!alive()) return; emit('changed'); message.value = '账号默认模型已更新。已有选择保持不变。' }
  catch { if (alive()) error.value = '默认模型未更新，请稍后重试。' } finally { saving.value = false }
}
function trapFocus(event) {
  if (event.key !== 'Tab') return
  const nodes = [...dialog.value.querySelectorAll('button:not(:disabled),input:not(:disabled),select:not(:disabled),[tabindex="0"]')].filter(node => node.getClientRects().length)
  if (!nodes.length) return
  if (event.shiftKey && document.activeElement === nodes[0]) { event.preventDefault(); nodes.at(-1).focus() }
  else if (!event.shiftKey && document.activeElement === nodes.at(-1)) { event.preventDefault(); nodes[0].focus() }
}
watch(() => auth.user?.id || auth.user?.username, () => { apiKey.value = ''; emit('close') })
onMounted(async () => { previousFocus = document.activeElement; edit(props.catalog.profiles?.[0]); await nextTick(); dialog.value?.querySelector('button')?.focus() })
onBeforeUnmount(() => { disposed = true; apiKey.value = ''; previousFocus?.focus?.() })
</script>
<template>
  <div class="model-modal-shade" @click.self="close">
    <section ref="dialog" class="model-manager" role="dialog" aria-modal="true" aria-labelledby="model-manager-title" data-testid="model-manager" @keydown.esc.stop.prevent="close" @keydown="trapFocus">
      <header><div><h2 id="model-manager-title">我的模型</h2><p>添加你自己的服务地址、API Key 和模型。</p></div><button class="icon-button" :disabled="saving" aria-label="关闭模型设置" @click="close"><StudioIcon name="close" :size="20" /></button></header>
      <div class="model-manager-body"><aside class="model-profile-list"><button class="model-new-profile" :disabled="saving" @click="edit(null)" data-testid="model-add"><StudioIcon name="plus" :size="17" />添加模型</button><button class="model-profile-row" :disabled="saving" @click="setDefault('system')"><strong>系统默认</strong><small>{{ catalog.defaultModelId === 'system' ? '账号默认' : '设为账号默认' }}</small></button><button v-for="profile in catalog.profiles" :key="profile.id" class="model-profile-row" :class="{ selected: activeId === profile.id }" :disabled="saving" @click="edit(profile)" :data-profile-id="profile.id"><strong>{{ profile.name }}</strong><small>{{ profile.model }}</small><span v-if="catalog.defaultModelId === profile.id">账号默认</span></button><p v-if="!catalog.profiles?.length" class="model-empty">还没有个人模型配置</p></aside>
        <form class="model-editor" @submit.prevent="save" autocomplete="off"><div class="model-form-heading"><h3>{{ isNew ? '添加模型' : '编辑模型' }}</h3><span v-if="existing?.hasKey">密钥已保存</span></div><label>名称<input v-model="name" maxlength="80" placeholder="例如：日常对话、代码助手" :disabled="saving" data-testid="model-name" required /></label><label>服务商<select v-model="provider" @change="preset" :disabled="saving" data-testid="model-provider"><option v-for="item in catalog.providers" :key="item.id" :value="item.id">{{ item.name }}</option></select></label><label>Base URL<input v-model="baseUrl" type="url" maxlength="2000" placeholder="https://api.example.com/v1" spellcheck="false" :disabled="saving" data-testid="model-base-url" required /></label><p class="model-field-help">使用服务商提供的 API 基础地址，模型请求由服务端发出。</p><label>模型 ID<input v-model="model" list="model-suggestions" maxlength="200" placeholder="输入服务商支持的模型 ID" spellcheck="false" :disabled="saving" data-testid="model-id" required /><datalist id="model-suggestions"><option v-for="item in providerInfo?.suggestedModels || []" :key="item" :value="item" /></datalist></label><p class="model-field-help">推荐项仅作填写参考，也可以输入自定义模型 ID。</p><label>API Key<input v-model="apiKey" type="password" autocomplete="new-password" maxlength="8192" :placeholder="existing?.hasKey ? '已保存 · 留空保留原密钥' : '输入 API Key'" spellcheck="false" :disabled="saving" data-testid="model-api-key" /></label><p class="model-field-help">密钥仅提交至当前部署后端，加密保存且不会回显；保存后清空输入。</p><p v-if="insecureConnection" class="model-field-help model-transport-note">当前连接未加密，请仅在可信网络使用；公网部署应启用 HTTPS。</p><p v-if="error" class="model-error" role="alert">{{ error }}</p><p v-if="message" class="model-success" role="status">{{ message }}</p><div class="model-save-actions"><button v-if="existing" type="button" class="quiet-button" :disabled="saving" @click="setDefault(activeId)" data-testid="model-set-default">设为账号默认</button><button type="submit" class="coding-primary" :disabled="saving || !valid" data-testid="model-save">{{ saving ? '正在保存…' : '保存配置' }}</button></div><div v-if="existing" class="model-delete-zone"><p v-if="confirmDelete">删除「{{ existing.name }}」及其密钥？历史任务保留，此模型将不能用于新任务。</p><button type="button" :disabled="saving" @click="remove" data-testid="model-delete">{{ confirmDelete ? '确认删除配置' : '删除此配置' }}</button><button v-if="confirmDelete" type="button" :disabled="saving" @click="confirmDelete = false">取消</button></div></form>
      </div>
    </section>
  </div>
</template>
<style scoped>
.model-modal-shade{position:fixed;inset:0;background:#17223855;display:grid;place-items:center;z-index:100;padding:24px;backdrop-filter:blur(3px)}.model-manager{width:min(780px,100%);max-height:90dvh;background:#fff;border-radius:20px;box-shadow:0 20px 100px #15243a33;overflow:hidden;display:flex;flex-direction:column}.model-manager>header{display:flex;justify-content:space-between;align-items:center;padding:24px 28px;border-bottom:1px solid #edf0f4;gap:18px}.model-manager h2{font-size:21px;font-weight:600;color:#243247}.model-manager header p{font-size:12px;color:#8a96a8;margin-top:9px}.model-manager-body{display:flex;min-height:0;overflow:auto}.model-profile-list{width:225px;flex-shrink:0;border-right:1px solid #edf0f4;padding:20px 15px;background:#fafbfd;overflow:auto}.model-new-profile{display:flex;align-items:center;gap:8px;border:1px solid #dce6f6;background:#fff;color:#447cc8;width:100%;border-radius:9px;padding:11px 12px;font-size:12px!important;margin-bottom:14px}.model-profile-row{display:block;text-align:left;background:transparent;border:0;width:100%;border-radius:9px;padding:12px 10px;margin:3px 0;color:#53647d;overflow-wrap:anywhere}.model-profile-row:hover,.model-profile-row.selected{background:#edf3fc}.model-profile-row strong{display:block;font-size:12px;font-weight:550}.model-profile-row small{display:block;font-size:10px;color:#97a4b7;margin-top:6px;line-height:1.5}.model-profile-row>span{display:inline-block;font-size:9px;color:#5984c6;margin-top:6px}.model-empty{font-size:11px;color:#a4adbb;padding:15px 8px;line-height:1.8}.model-editor{padding:24px 28px;flex:1;min-width:0;overflow:auto}.model-form-heading{display:flex;justify-content:space-between;gap:12px;align-items:center;margin-bottom:22px}.model-form-heading h3{font-size:15px;font-weight:550;color:#485973}.model-form-heading>span{color:#8c9cb2;font-size:10px}.model-editor label{font-size:12px;color:#62728a;display:block;margin-bottom:15px}.model-editor input,.model-editor select{display:block;margin-top:8px;width:100%;border:1px solid #e0e6ef;border-radius:8px;background:white;color:#3a4c65;padding:10px 12px;font-size:12px;min-width:0}.model-field-help{font-size:10px;color:#a1aab8;line-height:1.7;margin:-7px 0 16px}.model-save-actions{display:flex;justify-content:flex-end;gap:8px;align-items:center;margin-top:20px}.model-delete-zone{border-top:1px solid #edf0f5;margin-top:24px;padding-top:16px;display:flex;flex-wrap:wrap;gap:14px}.model-delete-zone button{border:0;background:none;color:#ad6560;font-size:11px;padding:5px 0}.model-delete-zone p{width:100%;font-size:11px;line-height:1.8;color:#ad6560}.model-error,.model-success{font-size:11px;line-height:1.8;padding:9px 12px;border-radius:8px}.model-error{background:#fff4f2;color:#a95750}.model-success{background:#f0f7f4;color:#608873}@media(max-width:760px){.model-modal-shade{padding:12px}.model-manager{max-height:94dvh;border-radius:15px}.model-manager>header{padding:19px}.model-manager h2{font-size:18px}.model-manager-body{flex-direction:column}.model-profile-list{width:100%;border-right:0;border-bottom:1px solid #edf0f4;max-height:145px;display:flex;gap:8px;padding:12px;flex-wrap:wrap;flex-shrink:0}.model-profile-row{width:140px;margin:0;padding:9px}.model-new-profile{width:140px;margin:0}.model-empty{padding:8px}.model-editor{padding:20px}.model-form-heading{margin-bottom:17px}.model-editor input,.model-editor select{font-size:14px}.model-manager header p{font-size:11px}}
</style>
