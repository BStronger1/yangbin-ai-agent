<script setup>
import {onBeforeUnmount,ref,watch} from 'vue'
import {codingApi} from '../api/coding'
const props=defineProps({sessionId:String,directory:Object,running:Boolean})
const rows=ref([]),preview=ref(null),error=ref('');let timer,generation=0
const checkedConfirmed=new Set()
function label(item){
  if(item.phase==='CONFIRMED')return item.baselineMatches===false?'候选已保存，原文件已变化':'候选已保存，原文件未应用'
  if(item.writerAlive)return '旧执行器仍可能写入，正在只读核对'
  if(item.phase==='PREPARED')return item.bytesMatch?'候选字节相符，执行结果待核对':'设备只记录了准备写入'
  return '等待原电脑回执'
}
async function refresh(token){
  if(token!==generation)return
  const directory=props.directory,device=directory?.candidateDevice,id=props.sessionId
  try{
    let items=await codingApi.localReceipts(id,device)
    if(token!==generation)return
    let changed=false
    for(const item of items.filter(r=>r.onDevice && (r.phase!=='CONFIRMED'||!checkedConfirmed.has(r.operationId)))){
      const receipt=await directory.receipt(id,item.turnId,item.operationId)
      if(token!==generation)return
      if(item.phase==='CONFIRMED')checkedConfirmed.add(item.operationId)
      if(receipt.phase==='MISSING'||(item.phase===receipt.phase && item.bytesMatch===receipt.bytesMatch && item.writerAlive===receipt.writerAlive && item.baselineMatches===receipt.baselineMatches))continue
      await codingApi.observeLocal(id,{clientId:device,requestId:item.operationId,ok:false,output:'',receipt});changed=true
    }
    if(changed)items=await codingApi.localReceipts(id,device)
    if(token===generation){rows.value=items;error.value=''}
  }catch(e){if(token===generation && e.response?.status!==404)error.value='设备回执暂不可用，正在重新连接。'}
  finally{if(token===generation&&(props.running||rows.value.some(r=>r.phase!=='CONFIRMED')||error.value))timer=setTimeout(()=>refresh(token),3500)}
}
watch(()=>[props.sessionId,props.directory,props.running],()=>{
  clearTimeout(timer);preview.value=null;checkedConfirmed.clear();void refresh(++generation)
},{immediate:true})
onBeforeUnmount(()=>{generation++;clearTimeout(timer)})
async function show(item){
  const id=props.sessionId
  try{const candidate=await codingApi.localCandidate(id,item.operationId);if(id===props.sessionId)preview.value={...candidate,id:item.operationId}}
  catch{error.value='未能取回该版本候选。'}
}
function download(){
  const c=preview.value
  const name=JSON.stringify('a/'+c.path),target=JSON.stringify('b/'+c.path)
  function lines(value){if(!value)return [];const parts=value.split('\n');if(parts.at(-1)==='')parts.pop();return parts}
  const before=lines(c.before),after=lines(c.after),rows=['diff --git '+name+' '+target]
  if(c.before===null)rows.push('new file mode 100644')
  rows.push('--- '+(c.before===null?'/dev/null':name),'+++ '+target)
  if(before.length||after.length){
    rows.push('@@ -'+(before.length?1:0)+','+before.length+' +'+(after.length?1:0)+','+after.length+' @@')
    for(const line of before)rows.push('-'+line)
    if(before.length&&!c.before.endsWith('\n'))rows.push('\\ No newline at end of file')
    for(const line of after)rows.push('+'+line)
    if(after.length&&!c.after.endsWith('\n'))rows.push('\\ No newline at end of file')
  }
  const url=URL.createObjectURL(new Blob([rows.join('\n')+'\n'],{type:'text/x-diff'})),a=document.createElement('a')
  a.href=url;a.download=c.path.split('/').at(-1)+'.patch';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000)
}
</script>
<template>
  <section v-if="rows.length||error" aria-label="本机候选与回执">
    <h2>本机候选与回执</h2><p v-if="error" role="status">{{ error }}</p>
    <div v-for="item in rows" :key="item.operationId"><strong>{{item.path}}</strong> · {{label(item)}}
      <button v-if="item.phase==='CONFIRMED'" class="quiet-button" @click="show(item)">查看候选</button>
    </div>
    <div v-if="preview"><p>基线 {{ preview.baseHash||'新文件' }} · 候选 {{ preview.afterHash }}</p>
      <details><summary>原文</summary><pre>{{preview.before}}</pre></details>
      <details open><summary>候选内容</summary><pre>{{preview.after}}</pre></details>
      <button class="quiet-button" @click="download">下载补丁</button><button class="quiet-button" @click="preview=null">收起</button>
    </div>
  </section>
</template>
<style scoped>
section { min-width: 0; overflow-wrap: anywhere; }
pre { white-space: pre-wrap; overflow: auto; max-height: 24rem; }
</style>
