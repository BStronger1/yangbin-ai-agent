<script setup>
import { computed, ref, watch } from 'vue'
import { auth, setUser } from '../auth'
import { api } from '../workspace-api'
import { useRouter, useRoute } from 'vue-router'
import StudioIcon from './StudioIcon.vue'
import { readWorkspace, workspaceLink } from '../workspace-state'
const router = useRouter(), route = useRoute(), expanded = ref(false), error = ref(''), busy = ref(false)
watch(() => route.fullPath, () => { expanded.value = false })
async function logout() { if (busy.value) return; busy.value = true; error.value = ''; try { await api.post('/auth/logout'); setUser(null); await router.replace('/login') } catch { error.value = '退出未完成，请稍后重试' } finally { busy.value = false } }
const codingSpace = computed(() => ['/coding','/work'].includes(route.path) && !route.params.skillId)
function switchWorkspace(target) { const selectedMode = readWorkspace('assistant', 'mode', 'chat'); router.push(workspaceLink(target === 'coding' ? 'coding' : 'assistant-' + selectedMode)) }
const links = [
{ to: '/assistant?mode=chat', name: '新 Chat 对话', icon: 'chat' },
{ to: '/assistant?mode=work', name: '新 Work 任务', icon: 'plus' },
{ to: '/coding', name: '新编程任务', icon: 'layout' },
{ to: '/skills', name: '插件 · 技能 · 伙伴', icon: 'layers' },
{ to: '/blog', name: '我的博客', icon: 'book' },
{ to: '/knowledge', name: '我的知识库', icon: 'folder' }
]
const active = to => {
  if (to.startsWith('/assistant?')) return route.path === '/assistant' && to.endsWith(route.query.mode === 'work' ? '=work' : '=chat')
  if (to === '/coding') return codingSpace.value
  if (to === '/work') return (route.path === '/' || route.path === '/work') && route.query.mode !== 'chat'
  if (to === '/super-agent') return route.path === '/super-agent' || route.query.mode === 'chat'
  if (to === '/skills') return (route.path.startsWith('/skills') && !route.path.startsWith('/skills/manage')) || route.path.startsWith('/partners/') || route.path === '/presentation'
  return route.path.startsWith(to)
}
</script>
<template>
  <header class="mobile-bar"><RouterLink to="/assistant?mode=chat" class="studio-brand">yangbin<span>AI</span></RouterLink><button @click="expanded = !expanded" :aria-expanded="expanded" aria-controls="studio-sidebar" aria-label="打开或关闭导航"><StudioIcon :name="expanded ? 'close' : 'menu'" /></button></header>
  <button v-if="expanded" class="sidebar-scrim" aria-label="关闭导航" @click="expanded = false"></button>
  <aside id="studio-sidebar" class="studio-sidebar" :class="{ expanded }">
    <RouterLink to="/assistant?mode=chat" class="studio-brand">yangbin<span>AI</span></RouterLink>
    <div class="sidebar-caption">YOUR WORK, WITH AI</div><div class="workspace-switcher" role="group" aria-label="顶层工作空间" data-testid="workspace-switcher"><button :class="{chosen:!codingSpace}" :aria-pressed="!codingSpace" data-testid="workspace-assistant" @click="switchWorkspace('assistant')"><StudioIcon name="spark" :size="18"/>通用助手</button><button :class="{chosen:codingSpace}" :aria-pressed="codingSpace" data-testid="workspace-coding" @click="switchWorkspace('coding')"><StudioIcon name="layout" :size="18"/>Coding Agent</button></div>
    <nav aria-label="主导航">
      <RouterLink v-for="link in links" :key="link.to" :to="link.to" :class="{ selected: active(link.to), 'new-work': link.to === '/work' }"><StudioIcon :name="link.icon" :size="21" /><span>{{ link.name }}</span></RouterLink>
      <div class="nav-label">历史与扩展</div>
      <RouterLink class="legacy-link" to="/super-agent" :class="{selected:route.path==='/super-agent'}"><StudioIcon name="clock" :size="20"/><span>旧版智能体对话</span></RouterLink>
      <RouterLink class="legacy-link" to="/tasks" :class="{ selected: active('/tasks') }"><StudioIcon name="clock" :size="20" /><span>出行任务记录</span></RouterLink>
      <RouterLink class="legacy-link" to="/planner" :class="{ selected: active('/planner') }"><StudioIcon name="map" :size="20" /><span>约会与出行规划</span></RouterLink>
      <RouterLink class="legacy-link" to="/love-master" :class="{ selected: active('/love-master') }"><StudioIcon name="chat" :size="20" /><span>情感问答</span></RouterLink>
      <RouterLink class="legacy-link" to="/skills/manage" :class="{ selected: route.path === '/skills/manage' }"><StudioIcon name="notes" :size="20" /><span>出行技能管理</span></RouterLink>
      <div class="nav-label">能力与设置</div>
      <RouterLink to="/download" :class="{ selected: active('/download') }" data-testid="desktop-download-entry"><StudioIcon name="layout" :size="20" /><span>下载客户端</span></RouterLink>
      <RouterLink to="/settings" :class="{ selected: active('/settings') }"><StudioIcon name="layers" :size="20" /><span>设置 · 模型与连接</span></RouterLink>
      <RouterLink to="/governance" :class="{ selected: active('/governance') }"><StudioIcon name="check" :size="20" /><span>用量与运行状态</span></RouterLink>
      <RouterLink to="/mcp" :class="{ selected: active('/mcp') }"><StudioIcon name="plug" :size="20" /><span>MCP 工具</span></RouterLink>
    </nav>
    <div class="sidebar-note"><span class="tiny-dot"></span>让想法成为一份具体成果</div>
    <footer class="sidebar-account">
      <RouterLink to="/account"><span class="account-avatar">{{ (auth.user?.username || '我').slice(0, 1) }}</span><span><strong>{{ auth.user?.username || '我的账号' }}</strong><small>{{ auth.user?.role === 'admin' ? '管理员' : '个人工作空间' }}</small></span></RouterLink>
      <button @click="logout" :disabled="busy" title="退出登录">退出</button><p v-if="error" role="alert">{{ error }}</p>
    </footer>
  </aside>
</template>
<style scoped>
.studio-sidebar{position:fixed;inset:0 auto 0 0;width:236px;background:var(--yb-bg-canvas);border-right:1px solid var(--yb-border);z-index:40;display:flex;flex-direction:column;padding:26px 14px 0;overflow-y:auto;color:var(--yb-ink-secondary)}
.studio-brand{display:flex;align-items:center;gap:8px;font-size:25px;letter-spacing:-1px;font-weight:750;padding:0 12px;color:var(--yb-ink)}.studio-brand span{font-size:11px;letter-spacing:.3px;padding:4px 7px;border-radius:var(--yb-radius-sm);background:var(--yb-accent-soft);border:1px solid #dbeafe;color:var(--yb-accent);font-weight:700}.sidebar-caption{font-size:9px;color:var(--yb-ink-muted);letter-spacing:1.7px;margin:12px 12px 28px}
.workspace-switcher{display:flex;flex-direction:column;gap:4px;margin:0 0 18px;padding:5px;border:1px solid var(--yb-border);border-radius:var(--yb-radius-md);background:var(--yb-bg-surface);box-shadow:var(--yb-shadow-sm)}.workspace-switcher button{display:flex;gap:9px;align-items:center;padding:10px;border:1px solid transparent;border-radius:var(--yb-radius-sm);background:transparent;color:var(--yb-ink-muted);font-size:12px;text-align:left}.workspace-switcher button:hover{background:var(--yb-bg-subtle)}.workspace-switcher button.chosen{background:var(--yb-accent-soft);border-color:#dbeafe;color:var(--yb-accent);font-weight:650}
.studio-sidebar nav{display:flex;flex-direction:column;gap:3px}.studio-sidebar nav a{position:relative;display:flex;align-items:center;gap:11px;font-size:13px;padding:11px 12px;border-radius:var(--yb-radius-sm);transition:background var(--yb-motion),color var(--yb-motion)}.studio-sidebar nav a svg{flex-shrink:0;opacity:.85}.studio-sidebar nav a:hover{background:var(--yb-bg-hover);color:var(--yb-ink)}.studio-sidebar nav a.selected{background:var(--yb-accent-soft);color:var(--yb-accent);font-weight:600}.studio-sidebar nav a.selected::before{content:'';position:absolute;left:0;top:13px;bottom:13px;width:3px;border-radius:var(--yb-radius-pill);background:var(--yb-accent)}.studio-sidebar nav a.new-work{background:var(--yb-bg-surface);border:1px solid var(--yb-border);box-shadow:var(--yb-shadow-sm);margin-bottom:12px}.studio-sidebar nav a.legacy-link{font-size:12px;color:var(--yb-ink-muted);padding-top:9px;padding-bottom:9px}.studio-sidebar nav a.legacy-link svg{width:17px;height:17px;opacity:.7}.studio-sidebar nav a.legacy-link.selected{color:var(--yb-accent)}.nav-label{font-size:10px;color:#8793a5;margin:22px 12px 7px;letter-spacing:.8px}
.sidebar-note{margin:auto 12px 20px;padding-top:30px;font-size:10px;color:var(--yb-ink-muted);display:flex;align-items:center;gap:8px}.tiny-dot{width:5px;height:5px;border-radius:50%;background:#94a3b8}.sidebar-account{display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap;gap:8px;border-top:1px solid var(--yb-border);padding:17px 5px}.sidebar-account>a{display:flex;align-items:center;gap:10px;min-width:0}.account-avatar{width:34px;height:34px;border-radius:var(--yb-radius-md);display:grid;place-items:center;background:var(--yb-bg-surface);border:1px solid var(--yb-border);color:var(--yb-ink-secondary);font-size:13px;font-weight:600}.sidebar-account strong{display:block;font-size:12px;max-width:115px;overflow:hidden;text-overflow:ellipsis;color:var(--yb-ink-secondary)}.sidebar-account small{display:block;color:var(--yb-ink-muted);font-size:10px;margin-top:4px}.sidebar-account button{border:0;background:transparent;font-size:11px;color:var(--yb-ink-muted);padding:6px;border-radius:6px}.sidebar-account button:hover{background:var(--yb-bg-hover)}.sidebar-account p{color:#b44343;font-size:12px}.mobile-bar,.sidebar-scrim{display:none}
@media(max-width:760px){.studio-sidebar{transform:translateX(-100%);transition:transform var(--yb-motion);width:260px;padding-top:26px}.studio-sidebar.expanded{transform:translateX(0)}.mobile-bar{height:60px;display:flex;align-items:center;justify-content:space-between;padding:0 18px;background:var(--yb-bg-surface);border-bottom:1px solid var(--yb-border);position:sticky;top:0;z-index:30}.mobile-bar .studio-brand{font-size:20px;padding:0}.mobile-bar button{display:flex;border:0;background:none;color:var(--yb-ink-secondary);padding:6px;border-radius:6px}.sidebar-scrim{display:block;position:fixed;inset:0;border:0;background:#0f172a55;backdrop-filter:blur(2px);z-index:35}}
</style>
