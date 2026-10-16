<script setup>
import {ref,onMounted,onBeforeUnmount,watch} from 'vue'
import {useRoute,useRouter} from 'vue-router'
import {auth} from '../auth'
import PlanningEvidence from '../components/PlanningEvidence.vue'
import PlanQuality from '../components/PlanQuality.vue'
import PlanRepair from '../components/PlanRepair.vue'
import WorkspaceLayout from '../components/WorkspaceLayout.vue'
import {api,errorMessage} from '../workspace-api'
const route=useRoute(),router=useRouter()
const form=ref({city:'上海',budget:500,date:new Date().toLocaleDateString('en-CA'),startTime:'14:00',hours:6,interests:'安静的咖啡馆、散步、轻松交流',transport:'公共交通',revision:''}),saved=ref([]),versions=ref([]),plan=ref(null),error=ref(''),busy=ref(false),exporting=ref(false),download=ref('')
const preview=ref(null),checking=ref(false)
const owner=()=>auth.user?.id||auth.user?.username||''
let alive=true,readEpoch=0,listEpoch=0,evidenceEpoch=0,readController,evidenceController
watch(()=>[form.value.city,form.value.date],()=>{preview.value=null;evidenceEpoch++;evidenceController?.abort();checking.value=false})
async function checkEvidence(){
 const current=++evidenceEpoch,account=owner(),city=form.value.city,date=form.value.date
 evidenceController?.abort();evidenceController=new AbortController();checking.value=true;error.value=''
 try{const {data}=await api.get('/ai/plans/evidence',{params:{city,date},timeout:65000,signal:evidenceController.signal});if(alive&&current===evidenceEpoch&&account===owner()&&city===form.value.city&&date===form.value.date)preview.value=data}
 catch(e){if(alive&&current===evidenceEpoch&&account===owner()&&e.code!=='ERR_CANCELED')error.value=errorMessage(e)}
 finally{if(alive&&current===evidenceEpoch&&account===owner())checking.value=false}
}
function placeFor(stop){return plan.value?.evidence?.places?.find(p=>p.id===stop.placeId)}
async function refresh(){const current=++listEpoch,account=owner();const {data}=await api.get('/ai/plans');if(alive&&current===listEpoch&&account===owner())saved.value=data}
function select(p){
 if(!p)return
 preview.value=null;plan.value=p;form.value={...p.requirements,revision:''};download.value=''
 if(route.query.plan!==p.id||String(route.query.version)!==String(p.version))router.replace({path:'/planner',query:{plan:p.id,version:String(p.version)}})
}
async function open(id,version){
 const current=++readEpoch,account=owner();readController?.abort();readController=new AbortController();error.value='';preview.value=null;download.value=''
 try{
  const {data}=await api.get('/ai/plans/'+encodeURIComponent(id),{signal:readController.signal})
  if(!alive||current!==readEpoch||account!==owner())return
  versions.value=data;const requested=Number(version);const selected=Number.isInteger(requested)&&requested>0?data.find(p=>p.version===requested):data[0]
  if(selected)select(selected);else{plan.value=null;error.value='找不到指定版本的方案，请选择已保存的方案。'}
 }catch(e){if(alive&&current===readEpoch&&account===owner()&&e.code!=='ERR_CANCELED'){plan.value=null;error.value=errorMessage(e)}}
}
async function generate(revise=false){
 if(busy.value||(revise&&plan.value.version!==versions.value[0]?.version))return
 busy.value=true;error.value='';download.value='';const current=readEpoch,account=owner()
 try{const response=revise?await api.post('/ai/plans/'+plan.value.id+'/revise',{expectedVersion:versions.value[0].version,requirements:form.value}):await api.post('/ai/plans',form.value);if(alive&&current===readEpoch&&account===owner()){await open(response.data.id);await refresh()}}
 catch(e){if(alive&&current===readEpoch&&account===owner())error.value=errorMessage(e)}finally{if(alive&&account===owner())busy.value=false}
}
async function pdf(){
 if(!plan.value||exporting.value)return
 exporting.value=true;error.value='';const id=plan.value.id,version=plan.value.version,account=owner()
 try{const {data}=await api.post('/ai/plans/'+id+'/'+version+'/pdf');if(alive&&account===owner()&&plan.value?.id===id&&plan.value?.version===version)download.value=data.url}
 catch(e){if(alive&&account===owner()&&plan.value?.id===id&&plan.value?.version===version)error.value=errorMessage(e)}finally{if(alive&&account===owner())exporting.value=false}
}
function newPlan(){readEpoch++;evidenceEpoch++;readController?.abort();evidenceController?.abort();checking.value=false;preview.value=null;plan.value=null;versions.value=[];download.value='';form.value.revision='';router.replace('/planner')}
async function repaired(result){
 if(plan.value?.id!==result.planId)return
 await open(result.planId,result.version)
 try{await refresh()}catch(e){if(alive)error.value=errorMessage(e)}
}
function readQuery(){
 if(typeof route.query.plan!=='string'||!route.query.plan)return
 if(plan.value?.id===route.query.plan&&String(plan.value.version)===String(route.query.version))return
 open(route.query.plan,route.query.version)
}
watch(()=>[route.query.plan,route.query.version],readQuery)
watch(()=>owner(),()=>{readEpoch++;listEpoch++;evidenceEpoch++;readController?.abort();evidenceController?.abort();plan.value=null;versions.value=[];saved.value=[];preview.value=null;download.value='';error.value='';busy.value=false;exporting.value=false;checking.value=false;form.value={city:'',budget:500,date:new Date().toLocaleDateString('en-CA'),startTime:'14:00',hours:6,interests:'',transport:'公共交通',revision:''};if(owner()){refresh().catch(()=>{});readQuery()}})
onMounted(()=>{refresh().catch(e=>{if(alive)error.value=errorMessage(e)});readQuery()})
onBeforeUnmount(()=>{alive=false;readEpoch++;listEpoch++;evidenceEpoch++;readController?.abort();evidenceController?.abort()})
</script>
<template><WorkspaceLayout title="约会规划" description="从你的时间、预算和偏好出发，生成可调整、可保存的约会方案。"><div v-if="error" class="error" role="alert">{{error}}</div><div class="workspace-grid"><aside><section class="panel"><div class="row"><h2>{{plan?'调整需求':'规划一次约会'}}</h2><button v-if="plan" class="secondary" @click="newPlan" :disabled="busy">新建</button></div><form @submit.prevent="generate(!!plan)"><label>中国城市<input v-model="form.city" required maxlength="80" :disabled="busy"></label><div class="split"><label>两人总预算（元）<input v-model.number="form.budget" type="number" min="1" max="100000" required :disabled="busy"></label><label>日期<input v-model="form.date" type="date" required :disabled="busy"></label></div><div class="split"><label>开始时间<input v-model="form.startTime" type="time" required :disabled="busy"></label><label>时长（小时）<input v-model.number="form.hours" type="number" min="1" max="12" required :disabled="busy"></label></div><label>交通方式<select v-model="form.transport" :disabled="busy"><option>公共交通</option><option>步行</option><option>自驾</option><option>打车</option></select></label><label>兴趣与偏好<textarea v-model="form.interests" maxlength="1000" :disabled="busy"></textarea></label><label v-if="plan">修改要求<textarea v-model="form.revision" maxlength="2000" placeholder="例如：改成雨天室内方案。预算减半时，请同时修改上方预算。" :disabled="busy"></textarea></label><button type="button" class="secondary" :disabled="checking||busy" @click="checkEvidence">{{checking?'正在查询…':'查询天气与地点依据'}}</button> <button v-if="!plan" :disabled="busy||checking">{{busy?'正在生成…':'生成方案'}}</button><button v-else type="submit" :disabled="busy||plan.version!==versions[0]?.version">{{busy?'正在调整…':'保存为新版本'}}</button><p v-if="plan&&plan.version!==versions[0]?.version" class="muted">正在查看旧版，请切回最新版再修改。</p><p class="muted" style="margin-top:12px">生成或修改会调用已配置的聊天模型，完成后自动保存。</p></form></section><section class="panel"><h2>已保存的方案</h2><button v-for="item in saved" :key="item.id" class="item" :disabled="busy" @click="open(item.id)">{{item.title}}<span>版本 {{item.version}} · {{new Date(item.createdAt).toLocaleDateString()}}</span></button><p v-if="!saved.length" class="muted">生成第一个方案后，会显示在这里。</p></section></aside><div><div class="notice">生成时自动查询天气和开放地图地点，并随方案保存来源快照。价格、交通耗时和当天营业状态仍需自行确认；免费数据源可能缺漏或暂时不可用。</div><PlanningEvidence v-if="preview" :evidence="preview" :checking="checking" @retry="checkEvidence"/><section v-if="busy" class="panel" role="status"><h2>正在准备方案…</h2><p>将结合输入需求和知识库资料生成方案，并校验预算与行程时间。</p></section><section v-if="plan" class="panel"><div class="row" style="justify-content:space-between"><span class="badge">已保存 · 版本 {{plan.version}}</span><select style="width:auto" :value="plan.version" :disabled="busy" aria-label="方案版本" @change="select(versions.find(v=>v.version===Number($event.target.value)))"><option v-for="v in versions" :key="v.version" :value="v.version">版本 {{v.version}}</option></select></div><h2 style="font-size:25px;margin-top:22px">{{plan.itinerary.title}}</h2><p>{{plan.itinerary.summary}}</p><div class="split" style="margin:24px 0"><div><p class="muted">预计支出 / 两人预算</p><div class="stat">¥{{plan.itinerary.stops.reduce((s,x)=>s+x.estimatedCost,0)}} <small class="muted">/ ¥{{plan.requirements.budget}}</small></div></div><div><p class="muted">{{plan.requirements.city}} · {{plan.requirements.date}}</p><h3>{{plan.requirements.startTime}} 开始 · {{plan.requirements.hours}} 小时</h3></div></div><div class="timeline"><article class="stop" v-for="(stop,i) in plan.itinerary.stops" :key="i"><span class="badge">{{stop.time}}</span><h3>{{stop.activity}}</h3><p>{{stop.note}}</p><p v-if="placeFor(stop)">地图候选地点：<a :href="placeFor(stop).url" target="_blank" rel="noopener noreferrer">{{placeFor(stop).name}}</a></p><p class="muted">估算费用 ¥{{stop.estimatedCost}}</p></article></div><h3>备选方案</h3><ul><li v-for="(item,i) in plan.itinerary.alternatives" :key="i">{{item}}</li></ul><h3>出行前确认</h3><ul><li v-for="(item,i) in plan.itinerary.cautions" :key="i">{{item}}</li></ul><PlanQuality :quality="plan.quality"/><PlanRepair :plan-id="plan.id" :version="plan.version" :quality="plan.quality" @repaired="repaired"/><PlanningEvidence :evidence="plan.evidence" snapshot/><details><summary>参考知识库 · {{plan.sources.length}} 条</summary><article v-for="(s,i) in plan.sources" :key="i" class="result"><h3>{{s.source}}</h3><p>{{s.content}}</p></article><p v-if="!plan.sources.length" class="muted">没有检索到相关资料</p></details><div class="row"><button :disabled="exporting||busy" @click="pdf">{{exporting?'正在导出…':'导出 PDF'}}</button><a v-if="download" class="button" :href="download" target="_blank" rel="noopener">打开 PDF</a></div></section><section v-else-if="!busy" class="panel" style="padding:55px 30px"><span class="badge">从需求到行程</span><h2 style="font-size:26px;margin-top:24px">给相处留出空间</h2><p>填写左侧需求，得到按时间安排的活动、费用估算和备选建议。方案可多次调整，每次修改都会保留上一版本。</p></section></div></div></WorkspaceLayout></template>