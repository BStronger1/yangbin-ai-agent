<script setup>
import { ref, watch, onBeforeUnmount } from 'vue'
import { auth } from '../auth'
import { api } from '../workspace-api'
const state = ref('loading')
let epoch = 0, controller
async function check() {
  const current = ++epoch
  controller?.abort(); controller = new AbortController(); state.value = 'loading'
  try {
    const {data:policy} = await api.get('/ai/work/partners/presentation/access',{signal:controller.signal})
    if(current !== epoch)return
    if(!policy.enabled){state.value='restricted';return}
    const { data } = await api.get('/ppt/status', { signal: controller.signal })
    if (current === epoch) state.value = data.ready ? 'ready' : data.enabled ? 'offline' : 'disabled'
  } catch { if (current === epoch) state.value = 'offline' }
}
watch(() => auth.user?.id, check, { immediate: true })
onBeforeUnmount(() => { epoch++; controller?.abort() })
</script>
<template>
  <main class="presentation-studio">
    <header><RouterLink to="/partners/presentation">← 演示伙伴</RouterLink><strong>NarrativePPT 演示工作台</strong><button @click="check">重新连接</button></header>
    <iframe v-if="state === 'ready' && auth.user" :key="auth.user.id" src="/ppt/" title="NarrativePPT 演示制作与编辑" allow="clipboard-write"></iframe>
    <section v-else role="status">
      <h1>{{ state === 'loading' ? '正在打开演示工作台…' : state === 'restricted' ? '演示工作台访问已限制' : state === 'disabled' ? '演示工作台尚未启用' : '演示服务暂时无法连接' }}</h1>
      <p>导入材料，设置听众与目标，审阅叙事大纲，逐页编辑并导出 PPTX。</p>
      <RouterLink v-if="state === 'restricted'" to="/partners/presentation/details?tab=management">前往伙伴管理调整授权</RouterLink><p v-else-if="state !== 'loading'">请联系管理员启动演示服务；现有文字演示功能仍可使用。</p>
      <RouterLink to="/work/presentation-outline">使用简版文字演示</RouterLink>
    </section>
  </main>
</template>
<style scoped>
.presentation-studio{height:100dvh;display:flex;flex-direction:column;background:#fff;min-width:0}
header{display:flex;gap:18px;align-items:center;flex-wrap:wrap;padding:15px 24px;border-bottom:1px solid #e4e8ef;font-size:13px}
header a{color:#64748b}header strong{flex:1;color:#29384e}header button{border:1px solid #e2e8f0;border-radius:8px;padding:8px 12px;background:white}
iframe{width:100%;flex:1;border:0;min-height:0}section{margin:80px auto;padding:24px;max-width:650px}h1{font-size:22px}p{margin:18px 0;color:#64748b;line-height:1.8}
@media(max-width:760px){.presentation-studio{height:calc(100dvh - 60px)}header{padding:12px;gap:10px}header strong{flex-basis:100%}}
</style>
