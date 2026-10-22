<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { auth } from '../auth'
import { api } from '../workspace-api'
const props=defineProps({sessionId:{type:String,required:true},turnId:{type:String,required:true},events:{type:Array,default:()=>[]},sessionStatus:String})
const emit=defineEmits(['updated'])
const active=new Set(['QUEUED','RUNNING','WAITING_APPROVAL','AWAITING_APPROVAL','CANCELLING','BLOCKED'])
const statusText=value=>({QUEUED:'排队中',RUNNING:'执行中',WAITING_APPROVAL:'等待审批',AWAITING_APPROVAL:'等待审批',CANCELLING:'正在取消',CANCELLED:'已取消',SUCCEEDED:'已完成',COMPLETED:'已完成',FAILED:'失败',PARTIAL:'部分完成',BLOCKED:'受阻',INTERRUPTED:'已中断'})[value]||'状态未知'
const roleText=value=>({manager:'管理者',executor:'执行者',reviewer:'独立审查者',worker:'执行者'})[value]||value||'待分配'
const scoped=computed(()=>props.events.filter(event=>event.turnId===props.turnId&&(event.type==='task'||event.type==='team')))
const projection=computed(()=>{
 const tasks=new Map();let result=null,budget=null,teamTaskId=''
 const merge=(identity,fields={})=>{if(!identity?.taskId)return;const previous=tasks.get(identity.taskId)||{};tasks.set(identity.taskId,{...previous,...fields,identity:{...previous.identity,...identity},progress:{...previous.progress,...Object.fromEntries(Object.entries(fields.progress||{}).filter(([,value])=>value!=null))}})}
 for(const event of scoped.value){
  if(event.type==='task')merge(event.identity,{status:event.status,progress:event.progress,error:event.error,artifacts:event.artifacts})
  if(event.type==='team'){
   if(event.teamTaskId)teamTaskId=event.teamTaskId
   if(event.budget)budget=event.budget
   if(event.identity)merge(event.identity,{progress:{memberId:event.memberId,role:event.role}})
   if(event.event==='result'||event.event==='completed'){result=event;for(const member of event.members||[])merge(member.identity,{status:member.status,progress:{memberId:member.memberId,role:member.role},error:member.error?{message:member.error}:null})}
   if(event.event==='member_blocked')for(const task of tasks.values())if(task.progress?.memberId===event.memberId)task.status='BLOCKED'
  }
 }
 const members=[...tasks.values()].filter(task=>task.identity.parentTaskId)
 const artifacts=new Map()
 for(const task of tasks.values())for(const artifact of task.artifacts||[])if(artifact?.id)artifacts.set(artifact.id,artifact)
 for(const artifact of result?.artifacts||[])if(artifact?.id)artifacts.set(artifact.id,artifact)
 return{members,result,budget,teamTaskId,status:result?.status||tasks.get(teamTaskId)?.status,artifacts:[...artifacts.values()]}
})
const visible=computed(()=>scoped.value.some(e=>e.type==='team')||projection.value.members.length>0)
const requested=ref(new Set()),cancelling=ref(new Set()),errors=ref({}),artifacts=ref({}),loading=ref(new Set())
let generation=0,disposed=false
const owner=()=>auth.user?.id||auth.user?.username
const storageKey=()=>`coding-team-cancel:${owner()}:${props.sessionId}:${props.turnId}`
const endpoint=()=>'/ai/coding/sessions/'+encodeURIComponent(props.sessionId)
const valid=token=>!disposed&&token===generation
const remaining=value=>Number.isFinite(value)&&value>=0?value:'未知'
const cost=computed(()=>{const b=projection.value.budget;return !b||b.costMicros==='UNKNOWN'||(!Number.isFinite(b.costMicros)||b.costMicros<0)?'未知':(b.costMicros/1000000).toLocaleString('zh-CN',{maximumFractionDigits:6})+'（'+(b.currency||'币种未提供')+'）'})
const canCancel=task=>active.has(props.sessionStatus)&&active.has(task.status)&&task.status!=='CANCELLING'&&!requested.value.has(task.identity.taskId)
async function cancelChild(task){
 if(!canCancel(task))return
 const token=generation,id=task.identity.taskId
 requested.value=new Set([...requested.value,id]);cancelling.value=new Set([...cancelling.value,id])
 // Record intent before sending; an uncertain response must never cause replay.
 try{sessionStorage.setItem(storageKey(),JSON.stringify([...requested.value]))}catch{/* Memory-only marker still prevents duplicate clicks in this view. */}
 try{const {data}=await api.post(endpoint()+'/children/'+encodeURIComponent(id)+'/cancel');if(valid(token)){errors.value={...errors.value,[id]:''};emit('updated',data)}}
 catch{if(valid(token))errors.value={...errors.value,[id]:'取消结果尚未确认，请等待任务状态刷新；不会重复发送取消请求。'}}
 finally{if(valid(token)){const next=new Set(cancelling.value);next.delete(id);cancelling.value=next}}
}
async function loadArtifact(artifact){
 if(loading.value.has(artifact.id)||artifacts.value[artifact.id])return
 const token=generation,id=artifact.id
 loading.value=new Set([...loading.value,id])
 try{
  const {data}=await api.get(endpoint()+'/turns/'+encodeURIComponent(props.turnId)+'/artifacts/'+encodeURIComponent(id))
  if(!valid(token))return
  if(!Number.isSafeInteger(artifact.size)||artifact.size<0||typeof artifact.sha256!=='string'||!/^[a-f0-9]{64}$/.test(artifact.sha256)||typeof data.content!=='string'||typeof data.name!=='string'||data.sha256!==artifact.sha256||new TextEncoder().encode(data.content).length!==artifact.size||artifact.size>65536)throw Error('Artifact mismatch')
  artifacts.value={...artifacts.value,[id]:data};errors.value={...errors.value,[id]:''}
 }catch{if(valid(token))errors.value={...errors.value,[id]:'产物无法读取或校验未通过，请刷新任务后核对。'}}
 finally{if(valid(token)){const next=new Set(loading.value);next.delete(id);loading.value=next}}
}
function download(artifact){
 const data=artifacts.value[artifact.id];if(!data)return
 const blob=new Blob([data.content],{type:'text/plain;charset=utf-8'}),url=URL.createObjectURL(blob),link=document.createElement('a')
 const name=data.name.split(/[\\/]/).at(-1).replace(/[\x00-\x1f<>:"|?*]/g,'_')||'artifact'
 link.href=url;link.download=name.endsWith('.txt')?name:name+'.txt';link.click();setTimeout(()=>URL.revokeObjectURL(url),1000)
}
watch([()=>props.sessionId,()=>props.turnId,owner],()=>{generation++;artifacts.value={};errors.value={};loading.value=new Set();cancelling.value=new Set();let ids=[];try{ids=JSON.parse(sessionStorage.getItem(storageKey())||'[]')}catch{}requested.value=new Set(Array.isArray(ids)?ids.filter(id=>typeof id==='string'):[])},{immediate:true,flush:'sync'})
onBeforeUnmount(()=>{disposed=true;generation++})
</script>
<template>
<section v-if="visible" class="coding-team" aria-label="团队执行进度" data-testid="coding-team">
 <header><div><h3>团队执行</h3><small>成员使用独立任务上下文；停止整个团队请使用任务的停止按钮。</small></div><strong data-testid="team-status">{{statusText(projection.status)}}</strong></header>
 <p v-if="projection.teamTaskId" class="team-parent">父任务 <code>{{projection.teamTaskId}}</code></p>
 <div v-if="projection.budget" class="team-budget" data-testid="team-budget"><span>请求 {{remaining(projection.budget.requests)}} / {{remaining(projection.budget.maxRequests)}}</span><span>Token 占用 {{remaining(projection.budget.tokens)}} / {{remaining(projection.budget.maxTokens)}}</span><span>费用：{{cost}}</span><small>Token 占用包含尚未确认用量的保守预留；未知费用不按零计算。</small></div><p v-else class="team-note">预算尚未报告。</p>
 <ol class="team-members"><li v-for="task in projection.members" :key="task.identity.taskId" :data-testid="'team-member-'+task.identity.taskId"><div class="team-member-title"><strong>{{task.progress?.memberId||task.identity.agentId}}</strong><span>{{roleText(task.progress?.role)}} · {{statusText(task.status)}}</span></div><details><summary>任务与父子关系</summary><p>Agent：<code>{{task.identity.agentId}}</code></p><p>子任务：<code>{{task.identity.taskId}}</code></p><p>父任务：<code>{{task.identity.parentTaskId}}</code></p></details><p v-if="task.error?.message" class="team-note">{{task.error.message}}</p><p v-if="errors[task.identity.taskId]" role="alert" class="team-error">{{errors[task.identity.taskId]}}</p><button v-if="active.has(sessionStatus) && active.has(task.status)" type="button" class="quiet-button" :disabled="!canCancel(task)" :data-testid="'team-cancel-'+task.identity.taskId" @click="cancelChild(task)">{{requested.has(task.identity.taskId)?'已请求取消此成员':'取消此成员'}}</button></li></ol>
 <div v-if="projection.artifacts.length" class="team-artifacts"><h4>团队产物</h4><article v-for="artifact in projection.artifacts" :key="artifact.id"><strong>{{artifact.name}}</strong><small>{{artifact.size}} 字节 · 来自子任务 {{artifact.taskId}}</small><details @toggle="$event.target.open&&loadArtifact(artifact)" :data-testid="'team-artifact-'+artifact.id"><summary>查看文本产物</summary><p v-if="loading.has(artifact.id)">正在核对产物…</p><p v-if="errors[artifact.id]" role="alert" class="team-error">{{errors[artifact.id]}}</p><template v-if="artifacts[artifact.id]"><pre :data-testid="'artifact-content-'+artifact.id">{{artifacts[artifact.id].content}}</pre><button type="button" class="quiet-button" @click="download(artifact)" :data-testid="'artifact-download-'+artifact.id">下载文本文件</button></template></details></article></div>
</section>
</template>
<style scoped>
.coding-team{margin:18px 0;padding:18px;border:1px solid #dbe5ef;border-radius:12px;background:#f9fbfe;color:#43556b;font-size:12px}.coding-team header{display:flex;justify-content:space-between;align-items:start;gap:12px}.coding-team h3{font-size:15px;margin:0 0 6px}.coding-team small,.team-note{font-size:11px;line-height:1.7;color:#6b7c91}.coding-team code{font-size:10px;overflow-wrap:anywhere}.team-parent{margin:12px 0}.team-budget{display:flex;gap:8px 18px;flex-wrap:wrap;padding:12px;background:#edf3fa;border-radius:8px}.team-budget small{width:100%}.team-members{padding:0;list-style:none}.team-members li{padding:13px 0;border-bottom:1px solid #e1e8f0}.team-member-title{display:flex;justify-content:space-between;gap:12px;flex-wrap:wrap}.coding-team details{margin-top:9px}.coding-team summary{cursor:pointer;color:#52769b}.coding-team details p{font-size:11px}.team-artifacts article{padding:12px;background:white;border:1px solid #e1e8f0;border-radius:8px;margin:10px 0;overflow-wrap:anywhere}.team-artifacts small{display:block;margin-top:5px}.team-artifacts pre{max-height:320px;overflow:auto;white-space:pre-wrap;overflow-wrap:anywhere;font-size:11px;background:#f2f5fa;padding:12px}.team-error{color:#9e4841;line-height:1.7}
</style>
