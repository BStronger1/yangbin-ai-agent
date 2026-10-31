<script setup>
import {onMounted,ref} from 'vue'
const release=ref(null),error=ref(''),loading=ref(false)
const current=window.yangbinDesktop?.appVersion
async function load(){
 loading.value=true;error.value=''
 try{
  const response=await fetch('/downloads/desktop.json',{cache:'no-store'})
  if(!response.ok)throw Error()
  const data=await response.json()
  if(!/^\d+\.\d+\.\d+$/.test(data.version)||data.filename!==`yangbin-ai-agent-windows-x64-${data.version}.zip`||!Number.isSafeInteger(data.bytes)||data.bytes<=0||!/^[a-f0-9]{64}$/.test(data.sha256))throw Error()
  release.value=data
 }catch{release.value=null;error.value='暂时无法获取客户端，请稍后重试。'}finally{loading.value=false}
}
onMounted(load)
</script>
<template>
 <main class="download-page">
  <header><RouterLink to="/assistant">yangbin AI</RouterLink><RouterLink to="/coding">返回工作台</RouterLink></header>
  <section class="download-card">
   <div class="eyebrow">DESKTOP FOR WINDOWS</div><h1>让 Coding Agent<br>直接处理本机项目</h1>
   <p class="intro">选择电脑上的文件夹，读写代码、启动项目，并在 VS Code 中查看改动。本地执行已内置，无需单独连接运行器。</p>
   <p v-if="current" class="current">当前客户端 {{current}}{{release?.version===current?' · 已是此下载入口的最新版本':''}}</p>
   <div v-if="release" class="download-action">
    <a :href="'/downloads/'+release.filename" :download="release.filename" target="_blank" rel="noopener" class="download-button" data-testid="download-windows">下载 Windows 客户端 <span>↗</span></a>
    <p>v{{release.version}} · Windows x64 · {{(release.bytes/1024/1024).toFixed(1)}} MB · 便携 ZIP</p>
   </div>
   <p v-else-if="loading" role="status">正在获取版本信息…</p>
   <div v-else role="alert"><p>{{error}}</p><button @click="load">重新获取</button></div>
   <ol><li>下载后解压完整文件夹。</li><li>更新前先结束任务、退出旧客户端，再启动新文件夹中的 <strong>yangbin AI Agent.exe</strong>。</li><li>登录原有账号，在 Coding Agent 中选择本机项目。新版本会沿用原有账号会话与项目目录绑定。</li></ol>
   <p class="note">目前提供 Windows x64 版本，macOS 与 Linux 客户端尚未发布。客户端需要联网，项目所需开发环境仍需自行安装。</p>
   <p class="note">便携版尚未配置代码签名，Windows 可能显示未知发布者。请核对下载来源与校验值；客户端不会自动安装更新。</p>
   <details v-if="release"><summary>SHA-256 校验值</summary><code>{{release.sha256}}</code></details>
  </section>
 </main>
</template>
<style scoped>
.download-page{min-height:100dvh;padding:28px 24px 60px;background:radial-gradient(ellipse at top,#edf4ff,#f8fafc 65%);color:#1e293b}.download-page header{max-width:980px;margin:auto;display:flex;justify-content:space-between;gap:20px}.download-page header a:first-child{font-weight:750}.download-page header a:last-child{color:#475569;font-size:14px}.download-card{max-width:720px;margin:64px auto 0;background:white;padding:44px;border:1px solid #e2e8f0;border-radius:22px;box-shadow:0 14px 50px #1e3a5f08}.eyebrow{color:#386ac9;font-size:12px;letter-spacing:2px;font-weight:700}h1{font-size:36px;line-height:1.35;margin:18px 0}.intro{font-size:16px;line-height:1.9;color:#536277}.download-action{margin:28px 0}.download-button{display:inline-flex;gap:24px;align-items:center;background:#2463eb;color:white;border-radius:10px;padding:15px 22px;font-weight:650}.download-action p,.current{font-size:13px;color:#64748b;margin-top:12px}ol{padding-left:22px;line-height:1.9;font-size:14px}li{margin:8px 0}.note{font-size:13px;line-height:1.8;color:#64748b;margin-top:20px}details{margin-top:22px;font-size:13px;color:#64748b}summary{cursor:pointer}code{display:block;overflow-wrap:anywhere;margin-top:10px}button{margin-top:12px;padding:8px 14px;background:white;border:1px solid #cbd5e1;border-radius:7px}@media(max-width:600px){.download-page{padding:22px 16px}.download-card{margin-top:32px;padding:26px 22px}h1{font-size:28px}.download-button{font-size:15px;gap:12px}}
</style>
