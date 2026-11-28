<script setup>
import {ref,watch,onBeforeUnmount} from 'vue'
import WorkspaceLayout from '../components/WorkspaceLayout.vue'
import {api,errorMessage} from '../workspace-api'
import {auth} from '../auth'
const documents=ref([]),selected=ref(null),file=ref(null),fileInput=ref(null),error=ref(''),busy=ref(false),searching=ref(false),query=ref(''),results=ref(null),elapsed=ref(0),evaluation=ref(null),evaluating=ref(false)
let poll,epoch=0,detailEpoch=0,listEpoch=0,searchEpoch=0
const current=t=>t===epoch&&!!auth.user?.id
function stopPoll(){clearInterval(poll);poll=undefined}
async function refresh(){if(!auth.user?.id)return;const t=epoch,request=++listEpoch;try{const {data}=await api.get('/ai/knowledge');if(current(t)&&request===listEpoch)documents.value=data}catch(e){if(current(t)&&request===listEpoch)error.value=errorMessage(e)}}
async function open(d){if(!d?.id||!auth.user?.id)return;const t=epoch,request=++detailEpoch;error.value='';try{const {data}=await api.get('/ai/knowledge/'+encodeURIComponent(d.id));if(current(t)&&request===detailEpoch)selected.value=data}catch(e){if(current(t)&&request===detailEpoch)error.value=errorMessage(e)}}
async function upload(){
 if(busy.value||!file.value||!auth.user?.id)return
 if(file.value.size>1024*1024){error.value='文件不能超过 1 MB，请缩小文件后重试。';return}
 const t=epoch,request=++detailEpoch,data=new FormData();data.append('file',file.value)
 busy.value=true;error.value='';stopPoll();poll=setInterval(()=>{if(current(t))refresh()},2000)
 try{const response=await api.post('/ai/knowledge',data);if(!current(t))return;if(request===detailEpoch)selected.value=response.data;file.value=null;if(fileInput.value)fileInput.value.value='';await refresh()}
 catch(e){if(current(t))error.value=errorMessage(e)}finally{if(t===epoch){busy.value=false;stopPoll()}}
}
async function remove(){
 const targetId=selected.value?.id;if(busy.value||!targetId||!auth.user?.id||!confirm('删除此文档及其检索索引？'))return
 const t=epoch,request=++detailEpoch;busy.value=true;error.value=''
 try{await api.delete('/ai/knowledge/'+encodeURIComponent(targetId));if(!current(t))return;if(request===detailEpoch&&selected.value?.id===targetId)selected.value=null;results.value=null;searchEpoch++;searching.value=false;await refresh()}
 catch(e){if(current(t))error.value=errorMessage(e)}finally{if(t===epoch)busy.value=false}
}
async function reindex(){
 const targetId=selected.value?.id;if(busy.value||!targetId||!auth.user?.id)return
 const t=epoch,request=++detailEpoch;busy.value=true;error.value=''
 try{const {data}=await api.post('/ai/knowledge/'+encodeURIComponent(targetId)+'/reindex');if(!current(t))return;if(request===detailEpoch&&selected.value?.id===targetId)selected.value=data;await refresh()}
 catch(e){if(current(t))error.value=errorMessage(e)}finally{if(t===epoch)busy.value=false}
}
async function search(){
 if(searching.value||!query.value.trim()||!auth.user?.id)return
 const t=epoch,request=++searchEpoch,needle=query.value.trim(),start=performance.now();searching.value=true;error.value=''
 try{const {data}=await api.get('/ai/rag/search',{params:{query:needle}});if(current(t)&&request===searchEpoch&&needle===query.value.trim()){results.value=data;elapsed.value=Math.round(performance.now()-start)}}
 catch(e){if(current(t)&&request===searchEpoch)error.value=errorMessage(e)}finally{if(t===epoch&&request===searchEpoch)searching.value=false}
}
async function evaluate(){if(evaluating.value||!auth.user?.id)return;const t=epoch;evaluating.value=true;error.value='';try{const {data}=await api.post('/ai/knowledge/evaluate');if(current(t))evaluation.value=data}catch(e){if(current(t))error.value=errorMessage(e)}finally{if(t===epoch)evaluating.value=false}}
watch(()=>auth.user?.id,()=>{epoch++;detailEpoch++;listEpoch++;searchEpoch++;stopPoll();documents.value=[];selected.value=null;file.value=null;if(fileInput.value)fileInput.value.value='';error.value='';busy.value=searching.value=evaluating.value=false;query.value='';results.value=evaluation.value=null;elapsed.value=0;if(auth.user?.id)refresh()},{immediate:true,flush:'sync'})
onBeforeUnmount(()=>{epoch++;stopPoll()})
const state=s=>({READY:'已索引',INDEXING:'索引中',FAILED:'失败'}[s]||s)
</script>
<template><WorkspaceLayout class="knowledge-page" title="知识库" description="管理参考资料，查看检索依据，让每一次回答有据可查。"><div v-if="error" class="error" role="alert">{{error}}</div><div class="workspace-grid"><aside><section class="panel"><h2>添加文档</h2><p class="muted">支持 UTF-8 Markdown / TXT、含文本的 PDF。单文件不超过 1 MB、60000 字符；扫描件请先 OCR。</p><label>选择文件<input ref="fileInput" type="file" accept=".md,.txt,.pdf" @change="file=$event.target.files[0]" :disabled="busy"></label><button @click="upload" :disabled="busy||!file">{{busy?'正在处理…':'上传并建立索引'}}</button></section><section class="panel"><div class="row"><h2>文档 · {{documents.length}}</h2><button class="secondary" @click="refresh().catch(e=>error=errorMessage(e))">刷新</button></div><button v-for="doc in documents" :key="doc.id" class="item" :class="{active:selected?.id===doc.id}" @click="open(doc)">{{doc.filename}}<span>{{doc.builtin?'内置':'上传'}} · {{state(doc.state)}} · {{doc.chunks}} 个片段</span></button><p v-if="!documents.length" class="muted">暂无文档或正在加载</p></section></aside><div><section class="panel"><h2>检索测试</h2><form @submit.prevent="search"><label>输入一个问题<input v-model="query" required maxlength="4000" placeholder="例如：64M 路由器 2-Pass LCP 的 P50 延迟是多少？"></label><button :disabled="searching||!query.trim()">{{searching?'检索中…':'检索知识库'}}</button></form><template v-if="results!==null"><p class="muted" style="margin-top:16px">返回 {{results.length}} 条 · {{elapsed}} ms · 相似度阈值 0.25</p><p v-if="!results.length" class="notice">未找到相关资料。可以补充文档或换一种问法。</p><article v-for="(item,i) in results" :key="i" class="result"><h3>[资料{{i+1}}] {{item.source}}</h3><span class="badge">相似度 {{Number(item.score).toFixed(3)}}</span><p>{{item.content}}</p></article></template></section><section v-if="selected" class="panel"><div class="row"><h2>{{selected.filename}}</h2><span class="badge" :class="{failed:selected.state==='FAILED'}">{{state(selected.state)}}</span></div><p v-if="selected.error" class="error">{{selected.error}}</p><pre>{{selected.text}}</pre><div v-if="!selected.builtin" class="row" style="margin-top:16px"><button class="secondary" :disabled="busy" @click="reindex">重新索引</button><button class="danger" :disabled="busy" @click="remove">删除文档</button></div></section><section class="panel"><h2>检索基准测试</h2><p class="muted">用固定 6 道问题检查内置文档是否进入前 3 条结果。仅测文件命中，不代表答案正确率；不调用聊天模型。</p><button class="secondary" :disabled="evaluating" @click="evaluate" style="margin-top:14px">{{evaluating?'评测中…':'运行 6 题测试'}}</button><template v-if="evaluation"><p class="stat" style="margin-top:16px">{{Math.round(evaluation.hitAt3*100)}}% <span class="muted">Hit@3</span></p><article v-for="row in evaluation.cases" :key="row.query" class="result"><h3>{{row.hit?'命中':'未命中'}} · {{row.query}}</h3><p class="muted">预期：{{row.expected}} · {{row.durationMs}} ms<br>返回：{{row.sources.join('、')||'无'}}</p></article></template></section></div></div></WorkspaceLayout></template>
<style scoped>
.knowledge-page .panel{box-shadow:var(--yb-shadow-sm);transition:border-color var(--yb-motion),box-shadow var(--yb-motion)}.knowledge-page .panel:hover{border-color:var(--yb-border-strong)}.knowledge-page .workspace-grid>*,.knowledge-page .workspace-grid>aside{min-width:0}.knowledge-page .item{padding:15px 12px;border-radius:var(--yb-radius-sm);margin:3px 0;transition:background var(--yb-motion),color var(--yb-motion)}.knowledge-page .item:hover{background:var(--yb-bg-subtle)}.knowledge-page .item.active{background:var(--yb-accent-soft);color:var(--yb-accent)}.knowledge-page input[type=file]{font-size:12px;padding:9px;background:var(--yb-bg-subtle)}.knowledge-page input[type=file]::file-selector-button{background:var(--yb-bg-surface);border:1px solid var(--yb-border);padding:6px 9px;margin-right:9px;border-radius:6px;color:var(--yb-ink-secondary);font:inherit;cursor:pointer}.knowledge-page .result{border-color:var(--yb-accent);background:var(--yb-bg-subtle);border-radius:0 var(--yb-radius-sm) var(--yb-radius-sm) 0;padding:12px 16px}.knowledge-page .result h3{overflow-wrap:anywhere}.knowledge-page .result .badge{margin-bottom:8px}.knowledge-page input::placeholder{color:#94a3b8;font-size:12px}.knowledge-page pre{font-family:var(--yb-font-mono)}
</style>
