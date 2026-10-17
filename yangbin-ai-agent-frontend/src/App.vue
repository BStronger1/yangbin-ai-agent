<script setup>
import WorkspaceNav from './components/WorkspaceNav.vue'
</script>

<template>
  <WorkspaceNav v-if="!$route.meta.public" />
  <div :class="{ 'app-content': !$route.meta.public }"><router-view /></div>
</template>

<style>
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
