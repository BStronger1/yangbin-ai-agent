<script setup>
import { ref, watch } from 'vue'
import { auth, setUser } from '../auth'
import { api } from '../workspace-api'
import { useRouter, useRoute } from 'vue-router'
import StudioIcon from './StudioIcon.vue'
const router = useRouter(), route = useRoute(), expanded = ref(false), error = ref('')
watch(() => route.fullPath, () => { expanded.value = false })
async function logout() { try { await api.post('/auth/logout'); setUser(null); router.replace('/login') } catch { error.value = '退出未完成，请稍后重试' } }
const links = [
{ to: '/work', name: '新工作任务', icon: 'plus' },
{ to: '/super-agent', name: '新对话', icon: 'chat' },
{ to: '/skills', name: '插件 · 技能 · 伙伴', icon: 'layers' },
{ to: '/knowledge', name: '我的知识库', icon: 'folder' }
]
const active = to => {
  if (to === '/work') return (route.path === '/' || route.path === '/work') && route.query.mode !== 'chat'
  if (to === '/super-agent') return route.path === '/super-agent' || route.query.mode === 'chat'
  if (to === '/skills') return route.path.startsWith('/skills') || route.path.startsWith('/partners/') || route.path === '/presentation'
  return route.path.startsWith(to)
}
</script>
<template>
  <header class="mobile-bar"><RouterLink to="/work" class="studio-brand">yangbin<span>AI</span></RouterLink><button @click="expanded = !expanded" :aria-expanded="expanded" aria-controls="studio-sidebar" aria-label="打开或关闭导航"><StudioIcon :name="expanded ? 'close' : 'menu'" /></button></header>
  <button v-if="expanded" class="sidebar-scrim" aria-label="关闭导航" @click="expanded = false"></button>
  <aside id="studio-sidebar" class="studio-sidebar" :class="{ expanded }">
    <RouterLink to="/work" class="studio-brand">yangbin<span>AI</span></RouterLink>
    <div class="sidebar-caption">YOUR WORK, WITH AI</div>
    <nav aria-label="主导航">
      <RouterLink v-for="link in links" :key="link.to" :to="link.to" :class="{ selected: active(link.to), 'new-work': link.to === '/work' }"><StudioIcon :name="link.icon" :size="21" /><span>{{ link.name }}</span></RouterLink>
      <div class="nav-label">出行工作台</div>
      <RouterLink to="/tasks" :class="{ selected: active('/tasks') }"><StudioIcon name="clock" :size="20" /><span>出行任务记录</span></RouterLink>
      <RouterLink to="/planner" :class="{ selected: active('/planner') }"><StudioIcon name="map" :size="20" /><span>约会与出行规划</span></RouterLink>
      <RouterLink to="/love-master" :class="{ selected: active('/love-master') }"><StudioIcon name="chat" :size="20" /><span>情感问答</span></RouterLink>
      <div class="nav-label">能力与设置</div>
      <RouterLink to="/governance" :class="{ selected: active('/governance') }"><StudioIcon name="check" :size="20" /><span>用量与运行状态</span></RouterLink>
      <RouterLink to="/mcp" :class="{ selected: active('/mcp') }"><StudioIcon name="plug" :size="20" /><span>MCP 工具</span></RouterLink>
      <RouterLink to="/skills/manage" :class="{ selected: route.path === '/skills/manage' }"><StudioIcon name="notes" :size="20" /><span>出行技能管理</span></RouterLink>
    </nav>
    <div class="sidebar-note"><span class="tiny-dot"></span>让想法成为一份具体成果</div>
    <footer class="sidebar-account">
      <RouterLink to="/account"><span class="account-avatar">{{ (auth.user?.username || '我').slice(0, 1) }}</span><span><strong>{{ auth.user?.username || '我的账号' }}</strong><small>{{ auth.user?.role === 'admin' ? '管理员' : '个人工作空间' }}</small></span></RouterLink>
      <button @click="logout" title="退出登录">退出</button><p v-if="error" role="alert">{{ error }}</p>
    </footer>
  </aside>
</template>
<style scoped>
.studio-sidebar{position:fixed;inset:0 auto 0 0;width:236px;background:#f7f8fa;border-right:1px solid #e9ebef;z-index:40;display:flex;flex-direction:column;padding:28px 16px 0;overflow-y:auto;color:#303642}.studio-brand{display:flex;align-items:center;gap:7px;font-size:25px;letter-spacing:-1px;font-weight:750;padding:0 12px;color:#171e2d}.studio-brand span{font-size:12px;letter-spacing:0;padding:4px 6px;border-radius:6px;background:#e6ecff;color:#3d61dc;font-weight:700}.sidebar-caption{font-size:9px;color:#8f98a8;letter-spacing:1.7px;margin:12px 12px 33px}.studio-sidebar nav{display:flex;flex-direction:column;gap:5px}.studio-sidebar nav a{display:flex;align-items:center;gap:12px;font-size:14px;padding:12px;border-radius:10px;transition:background .15s}.studio-sidebar nav a:hover{background:#eef0f4}.studio-sidebar nav a.selected{background:#e9edf5;color:#253e76;font-weight:600}.studio-sidebar nav a.new-work{background:#fff;border:1px solid #e1e5ed;box-shadow:0 2px 3px #101b2b03;margin-bottom:12px}.nav-label{font-size:11px;color:#9a9fa9;margin:26px 12px 7px;letter-spacing:.5px}.sidebar-note{margin:auto 12px 22px;padding-top:35px;font-size:11px;color:#939ba6;display:flex;align-items:center;gap:8px}.tiny-dot{width:6px;height:6px;border-radius:50%;background:#8badab}.sidebar-account{display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap;gap:8px;border-top:1px solid #e4e7ed;padding:20px 5px}.sidebar-account>a{display:flex;align-items:center;gap:10px;min-width:0}.account-avatar{width:35px;height:35px;border-radius:50%;display:grid;place-items:center;background:#e5ecfa;color:#7085b1}.sidebar-account strong{display:block;font-size:12px;max-width:115px;overflow:hidden;text-overflow:ellipsis}.sidebar-account small{display:block;color:#98a0ad;font-size:10px;margin-top:5px}.sidebar-account button{border:0;background:transparent;font-size:11px;color:#8c94a2}.sidebar-account p{color:#b44343;font-size:12px}.mobile-bar,.sidebar-scrim{display:none}@media(max-width:760px){.studio-sidebar{transform:translateX(-100%);transition:transform .2s;width:260px;padding-top:26px}.studio-sidebar.expanded{transform:translateX(0)}.mobile-bar{height:60px;display:flex;align-items:center;justify-content:space-between;padding:0 18px;background:white;border-bottom:1px solid #edf0f4;position:sticky;top:0;z-index:30}.mobile-bar .studio-brand{font-size:20px;padding:0}.mobile-bar button{display:flex;border:0;background:none;color:#333}.sidebar-scrim{display:block;position:fixed;inset:0;border:0;background:#17223b55;z-index:35}}
</style>
