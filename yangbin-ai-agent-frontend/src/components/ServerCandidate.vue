<script setup>
import { ref, watch } from 'vue'
import { api, errorMessage } from '../workspace-api'
import { downloadBlob } from '../downloads'

const props = defineProps({sessionId:String,turnId:String})
const changes=ref([]), receipts=ref([]), loading=ref(false), working=ref(false), error=ref('')
const attempts=new Map()
const root=()=>'/ai/coding/sessions/'+encodeURIComponent(props.sessionId)+'/turns/'+encodeURIComponent(props.turnId)
let ticket=0
async function load() {
  const active=++ticket; loading.value=true; error.value=''
  try {
    const [files,applications]=await Promise.all([api.get(root()+'/candidate'),api.get(root()+'/applications')])
    if(active===ticket){changes.value=files.data;receipts.value=applications.data}
  } catch(e){if(active===ticket)error.value=errorMessage(e)}
  finally{if(active===ticket)loading.value=false}
}
watch(()=>[props.sessionId,props.turnId],load,{immediate:true})
function receipt(change) {return receipts.value.find(x=>x.path===change.path)}
async function digest(value) {
  if(value==null)return null
  const bytes=await crypto.subtle.digest('SHA-256',new TextEncoder().encode(value))
  return Array.from(new Uint8Array(bytes),b=>b.toString(16).padStart(2,'0')).join('')
}
async function apply(change) {
  if(working.value)return
  working.value=true;error.value=''
  try {
    if(!attempts.has(change.path))attempts.set(change.path,crypto.randomUUID())
    await api.post(root()+'/applications',{requestId:attempts.get(change.path),path:change.path,oldHash:await digest(change.before),newHash:await digest(change.after)})
    await load()
  } catch(e){error.value=errorMessage(e)}
  finally{working.value=false}
}
function download(change) {
  downloadBlob(new Blob([change.after],{type:'text/plain;charset=utf-8'}), change.path.split('/').at(-1))
}
</script>
<template>
  <section aria-label="候选修复" class="server-candidate">
    <strong>{{loading?'读取候选…':changes.length?'候选修复已保存':'未发现候选文件改动'}}</strong>
    <p v-if="error" role="alert">{{error}} <button type="button" @click="load">重新核对</button></p>
    <details v-for="c in changes" :key="c.path">
      <summary>{{c.path}} · {{receipt(c)?.phase==='CONFIRMED'?'已记录应用回执':receipt(c)?'应用结果待核对':'尚未应用'}}</summary>
      <p>基线内容</p><pre>{{c.before ?? '原文件不存在'}}</pre><p>候选内容</p><pre>{{c.after ?? '拟删除文件'}}</pre>
      <button v-if="c.after!=null" type="button" @click="download(c)">下载候选文件</button>
      <button v-if="!receipt(c)" type="button" :disabled="working" @click="apply(c)">将这项改动应用到服务端项目</button>
    </details>
  </section>
</template>
<style scoped>
.server-candidate{margin:.8rem 0;padding:.65rem 1rem;border-left:3px solid #728da0}.server-candidate summary{cursor:pointer}.server-candidate pre{max-height:16rem;overflow:auto}.server-candidate button{margin-right:.6rem}
</style>
