<script setup>
import WorkspaceNav from './components/WorkspaceNav.vue'
import { ref } from 'vue'
import { useRouter } from 'vue-router'
const ready = ref(false), failed = ref(false)
useRouter().isReady().then(() => { ready.value = true }).catch(() => { failed.value = true })
const reload = () => window.location.reload()
</script>

<template>
  <main v-if="!ready" class="app-loading" role="status" aria-live="polite"><strong>yangbin AI</strong><p>{{ failed ? '页面未能加载，请重新连接。' : '正在打开工作空间…' }}</p><button v-if="failed" @click="reload">重新连接</button></main>
  <template v-else>
    <WorkspaceNav v-if="!$route.meta.public" />
    <div :class="{ 'app-content': !$route.meta.public }"><router-view v-slot="{ Component }"><component :is="Component" :key="$route.path === '/super-agent' ? 'legacy-manus' : $route.path === '/coding' ? 'coding' : $route.matched[0]?.path" /></router-view></div>
  </template>
</template>

<style>
.app-loading{min-height:100dvh;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:16px;color:#64748b;background:#f7f8fa}.app-loading strong{color:#17223b;font-size:24px}.app-loading button{padding:10px 18px;border:1px solid #dce3ef;border-radius:8px;background:white;color:#334155}
.app-content{margin-left:236px;min-width:0;min-height:100vh}
@media(max-width:760px){.app-content{margin-left:0;min-height:calc(100vh - 60px)}}
* {
  box-sizing: border-box;
  margin: 0;
  padding: 0;
}

html, body {
  font-family: 'PingFang SC', 'Microsoft YaHei', 'Helvetica Neue', Helvetica, Arial, sans-serif;
  font-size: 16px;
  color: #333;
  background-color: #f0f2f5;
  width: 100%;
  height: auto;
  min-height: 100vh;
  overflow-x: hidden;
}

#app {
  width: 100%;
  height: auto;
  min-height: 100vh;
}

a {
  text-decoration: none;
  color: inherit;
}

button {
  cursor: pointer;
}

/* 响应式字体大小 */
@media (max-width: 768px) {
  html, body {
    font-size: 15px;
  }
}

@media (max-width: 480px) {
  html, body {
    font-size: 14px;
  }
}

/* 滚动条样式 */
::-webkit-scrollbar {
  width: 6px;
  height: 6px;
}

::-webkit-scrollbar-track {
  background: #f1f1f1;
  border-radius: 3px;
}

::-webkit-scrollbar-thumb {
  background: #ccc;
  border-radius: 3px;
}

::-webkit-scrollbar-thumb:hover {
  background: #aaa;
}
</style>
