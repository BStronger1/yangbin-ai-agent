<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import StudioIcon from './StudioIcon.vue'
import { useOverlay } from '../composables/useOverlay'
import { editorUrl, normalizeEditorPath, readEditorPath, saveEditorPath } from '../editor-link'
const props = defineProps({ project: Object, owner: String, directoryName: String, desktopId: String })
const launcher = ref(null)
let opener = null
const open = ref(false), panel = ref(null), path = ref(''), saved = ref(''), notice = ref('')
const local = computed(() => props.project?.source === 'local')
const validation = computed(() => { try { return { path: normalizeEditorPath(path.value), url: editorUrl(path.value) } } catch (e) { return { error: e.message } } })
const savedUrl = computed(() => { try { return saved.value && editorUrl(saved.value) } catch { return '' } })
function close() { open.value = false; nextTick(() => { if (opener && !opener.isConnected) launcher.value?.focus() }) }
async function configure() { if(props.desktopId){try{await window.yangbinDesktop.openEditor(props.desktopId)}catch(e){notice.value=e.message;open.value=true}return} opener = document.activeElement; path.value = saved.value; notice.value = ''; open.value = true }
watch(() => [props.owner, props.project?.id, props.project?.source], () => {
  open.value = false; saved.value = local.value ? readEditorPath(props.owner, props.project?.id) : ''; path.value = saved.value; notice.value = ''
}, { immediate: true })
useOverlay(open, panel, close)
function saveAndOpen(event) {
  if (!local.value || !validation.value.url) { event.preventDefault(); return }
  saved.value = validation.value.path
  try { saveEditorPath(props.owner, props.project.id, saved.value); notice.value = '已请求打开 VS Code。若没有打开，请确认已安装 VS Code，并允许浏览器打开外部应用。' }
  catch { notice.value = '已请求打开 VS Code；浏览器未能保存路径，下次需要重新填写。' }
}
function forget() {
  try { saveEditorPath(props.owner, props.project.id, ''); saved.value = ''; path.value = ''; notice.value = '已清除当前项目的编辑器路径。' }
  catch { notice.value = '路径未能清除，请检查浏览器存储设置。' }
}
</script>

<template>
  <div class="editor-launcher">
    <a ref="launcher" v-if="!desktopId && local && savedUrl" :href="savedUrl" class="quiet-button" aria-label="用 VS Code 打开项目" title="用 VS Code 打开整个项目目录" data-testid="coding-vscode"><StudioIcon name="layout" :size="18" /><span>VS Code</span></a>
    <button ref="launcher" v-else class="quiet-button" aria-label="用 VS Code 打开项目" title="用 VS Code 打开项目" data-testid="coding-vscode" @click="configure"><StudioIcon name="layout" :size="18" /><span>VS Code</span></button>
    <button v-if="!desktopId && local && savedUrl" class="icon-button editor-settings" aria-label="设置 VS Code 项目路径" title="设置 VS Code 项目路径" data-testid="coding-vscode-settings" @click="configure">⌄</button>
  </div>
  <Teleport to="body">
    <div v-if="open" class="assistant-dialog-backdrop editor-backdrop" @click.self="close">
      <section ref="panel" class="editor-dialog" role="dialog" aria-modal="true" aria-labelledby="editor-dialog-title">
        <header><h2 id="editor-dialog-title">在 VS Code 中打开项目</h2><button class="icon-button" aria-label="关闭 VS Code 设置" @click="close"><StudioIcon name="close" :size="20" /></button></header>
        <p class="editor-project-name">{{ project?.name }}</p>
        <template v-if="local">
          <p>打开整个项目文件夹，浏览源码并查看改动。首次填写路径，以后可从右上角直接打开。</p>
          <label for="editor-project-path">本机项目目录</label>
          <input id="editor-project-path" v-model="path" data-overlay-initial data-testid="editor-project-path" autocomplete="off" spellcheck="false" placeholder="例如 C:\Projects\my-app" :aria-invalid="!!path && !!validation.error" aria-describedby="editor-path-help editor-path-error" />
          <p id="editor-path-help" class="editor-help">{{ directoryName ? `请填写已授权文件夹「${directoryName}」的完整路径。` : '请填写此项目在电脑上的完整目录路径。' }}可从文件资源管理器地址栏复制。浏览器无法自动获取或核对完整路径，请确认与已授权目录一致。路径仅保存在当前浏览器，不发送给服务端或模型。</p>
          <p v-if="path && validation.error" id="editor-path-error" class="editor-error" role="alert">{{ validation.error }}</p>
          <p class="editor-help">请保持 Agent 页面打开。在 VS Code 保存后可回到“项目文件”刷新；Agent 将检查覆盖冲突。</p>
          <p v-if="notice" role="status" class="editor-notice">{{ notice }}</p>
          <footer><button v-if="saved" class="quiet-button" @click="forget">清除路径</button><button class="quiet-button" @click="close">关闭</button><a v-if="validation.url" :href="validation.url" class="coding-primary editor-open" data-testid="editor-save-open" @click="saveAndOpen">保存并打开 VS Code</a><button v-else class="coding-primary" disabled>保存并打开 VS Code</button></footer>
        </template>
        <template v-else>
          <p>此项目保存在服务端，没有与本机文件夹建立连接。</p>
          <p>你可以在“项目文件”查看和编辑现有代码；若要直接用本机 VS Code 操作同一份文件，请新建任务，在“选择项目”中添加本机文件夹。</p>
          <p class="editor-help">服务端项目不会自动同步到本机。仅打开一个同名文件夹不会同步这里的改动。</p>
          <footer><button class="coding-primary" @click="close">知道了</button></footer>
        </template>
      </section>
    </div>
  </Teleport>
</template>

<style scoped>
.editor-launcher{display:flex;align-items:center;flex-shrink:0}.editor-launcher a{text-decoration:none}.editor-settings{padding:6px 3px}
.editor-backdrop{position:fixed;inset:0;background:#16243e55;display:grid;place-items:center;padding:20px;z-index:1200}
.editor-dialog{background:#fff;border:1px solid #e4e9f0;border-radius:18px;padding:24px;width:min(560px,100%);max-height:90dvh;overflow:auto;color:#263245;box-shadow:0 24px 80px #15264333;box-sizing:border-box}
.editor-dialog header,.editor-dialog footer{display:flex;align-items:center;gap:10px;justify-content:space-between}.editor-dialog h2{font-size:20px;margin:0}.editor-dialog p{font-size:14px;line-height:1.7}.editor-dialog label{display:block;font-size:14px;margin:18px 0 8px}.editor-dialog input{box-sizing:border-box;width:100%;font:inherit;padding:12px;border:1px solid #b5c2d6;border-radius:9px}.editor-dialog footer{justify-content:flex-end;flex-wrap:wrap;margin-top:20px}.editor-dialog .editor-help{font-size:12px;color:#68788e}.editor-project-name{font-weight:600;overflow-wrap:anywhere}.editor-dialog .editor-error{color:#aa3030}.editor-notice{color:#296441;background:#eef8f1;padding:10px;border-radius:8px}.editor-open{text-decoration:none;display:inline-flex;align-items:center}.editor-dialog :focus-visible{outline:3px solid #b6cef9;outline-offset:3px}
@media(max-width:760px){.editor-launcher .quiet-button span{display:none}.editor-dialog{padding:18px}.editor-backdrop{padding:12px}}
</style>
