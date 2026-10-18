<script setup>
import {ref,computed,watch,onBeforeUnmount} from 'vue'
import {useRoute} from 'vue-router'
import {api,errorMessage} from '../workspace-api'
import {auth} from '../auth'
import PartnerIdentity from '../components/PartnerIdentity.vue'
import '../studio.css'
import '../partner.css'
const route=useRoute(),data=ref(null),tab=ref('instructions'),sub=ref('identity'),ability=ref('skills'),error=ref(''),notice=ref(''),busy=ref(false)
const mode=ref('ALLOW'),enabled=ref(true),profile=ref({version:0,instructions:''})
const narrative=computed(()=>data.value?.partner.workspace==='narrative-ppt')
let epoch=0,controller
async function load(){const t=++epoch;controller?.abort();controller=new AbortController();data.value=null;error.value='';notice.value='';busy.value=false;sub.value=route.query.profile==='1'?'user':'identity';ability.value='skills';tab.value=route.query.tab==='management'?'management':'instructions'
try{const [r,p]=await Promise.all([api.get('/ai/work/partners/'+encodeURIComponent(route.params.partnerId),{signal:controller.signal}),api.get('/ai/work/partners/'+encodeURIComponent(route.params.partnerId)+'/profile',{signal:controller.signal})]);if(t!==epoch)return;profile.value=p.data;data.value=r.data;mode.value=r.data.policy.mode;enabled.value=r.data.policy.enabled}catch(e){if(t===epoch&&e.code!=='ERR_CANCELED')error.value=errorMessage(e)}}
async function save(){if(busy.value)return;const t=epoch;busy.value=true;error.value='';notice.value='';try{const r=await api.put('/ai/work/partners/'+encodeURIComponent(data.value.partner.id)+'/policy',{mode:mode.value,enabled:enabled.value},{signal:controller.signal});if(t!==epoch)return;data.value.policy=r.data;mode.value=r.data.mode;enabled.value=r.data.enabled;notice.value='授权设置已保存，对之后发起的操作生效。'}catch(e){if(t===epoch&&e.code!=='ERR_CANCELED')error.value=errorMessage(e)}finally{if(t===epoch)busy.value=false}}
async function saveProfile(){if(busy.value)return;const t=epoch;busy.value=true;notice.value='';error.value='';try{const r=await api.put('/ai/work/partners/'+encodeURIComponent(data.value.partner.id)+'/profile',profile.value,{signal:controller.signal});if(t!==epoch)return;profile.value=r.data;notice.value='偏好已保存，从下一条消息开始生效。'}catch(e){if(t===epoch&&e.code!=='ERR_CANCELED')error.value=errorMessage(e)}finally{if(t===epoch)busy.value=false}}
watch([()=>route.params.partnerId,()=>auth.user?.id],load,{immediate:true})
onBeforeUnmount(()=>{epoch++;controller?.abort()})
</script>
<template>
<main class="studio partner-page">
 <header class="studio-top partner-breadcrumb"><RouterLink to="/skills?tab=partners">工作伙伴</RouterLink><span>›</span><RouterLink :to="'/partners/'+route.params.partnerId">{{data?.partner.name||'伙伴'}}</RouterLink><span>›</span><strong>详情</strong></header>
 <div class="partner-details-body">
  <p v-if="error" class="studio-error" role="alert">{{error}}</p>
  <template v-if="data">
   <PartnerIdentity :partner="data.partner" details/>
   <nav class="partner-tabs" aria-label="伙伴详情"><button v-for="[id,label] in [['instructions','指令'],['abilities','能力'],['management','管理']]" :key="id" :class="{active:tab===id}" :aria-pressed="tab===id" @click="tab=id">{{label}}</button></nav>
   <template v-if="tab==='instructions'">
    <nav class="partner-pills" aria-label="指令类型"><button v-for="[id,label] in [['identity','伙伴档案'],['user','用户档案'],['behavior','行为准则']]" :class="{active:sub===id}" @click="sub=id">{{label}}</button></nav>
    <article class="partner-document" v-if="sub==='identity'"><h2>IDENTITY.md · {{data.partner.name}}</h2><dl><dt>身份定位</dt><dd>{{data.partner.description}}</dd><dt>领域</dt><dd>{{data.partner.category}}</dd><dt>工作指令</dt><dd><pre>{{data.instructions}}</pre></dd><dt>交付内容</dt><dd>{{narrative?'可在线编辑的演示页面、演讲者备注和 PPTX 文件':data.skill.output}}</dd></dl></article>
    <article class="partner-document" v-else-if="sub==='user'"><h2>用户档案</h2><dl><dt>当前账号</dt><dd>{{auth.user?.username||auth.user?.id}}</dd><dt>资料范围</dt><dd>会话与偏好按账号和伙伴独立保存。仅带入当前会话的有限上下文，不自动学习其他会话。</dd></dl><form class="partner-profile" @submit.prevent="saveProfile"><label for="partner-preferences">这个伙伴需要记住的偏好</label><textarea id="partner-preferences" v-model="profile.instructions" maxlength="2000" rows="7" placeholder="例如：我是产品经理；优先给结论；使用简洁中文；报告以表格列出行动项。"></textarea><p class="studio-note">最多 2000 字，仅用于此伙伴的文字对话；不会改写历史记录或同步到 PPT 工作台。清空后保存即可移除偏好。</p><button type="submit" class="studio-btn primary" :disabled="busy">{{busy?'保存中…':'保存我的偏好'}}</button><p v-if="notice" role="status">{{notice}}</p></form></article>
    <article class="partner-document" v-else><h2>行为准则</h2><pre>{{data.behavior}}</pre><p class="studio-note">{{narrative?'具体生成指令由工作台各步骤组织。':'这些系统准则与技能指引用于当前伙伴对话。'}}新设置不会改写已有任务快照。</p></article>
   </template>
   <template v-else-if="tab==='abilities'">
    <nav class="partner-pills"><button :class="{active:ability==='skills'}" @click="ability='skills'">技能</button><button :class="{active:ability==='connectors'}" @click="ability='connectors'">连接器</button></nav>
    <div v-if="ability==='skills'" class="partner-abilities"><article v-for="s in data.skills||[data.skill]" :key="s.id" class="partner-ability"><span class="capability-icon" :class="data.partner.color">{{data.partner.initial}}</span><div><h2>{{s.name}}</h2><p>{{s.description}}</p><span class="capability-tag">{{s.output}}</span></div></article><p class="studio-note">所有伙伴支持个人模型选择、Word/文字PDF材料读取、可编辑Word及表格Excel交付；数据表另可生成程序统计工作簿与预览图。图片仅保存预览。</p><p v-if="narrative" class="studio-note">实际PPT页面编辑与PPTX导出使用独立NarrativePPT工作台及其模型配置。</p></div>
    <div v-else class="partner-resource"><h2>{{narrative?'NarrativePPT 服务':'暂未绑定外部连接器'}}</h2><p>{{narrative?'复用当前账号连接专用演示服务，项目和材料按账号隔离。':'知识库、MCP 当前在各自入口使用，尚未自动加入此伙伴任务。'}}</p></div>
   </template>
   <form v-else class="partner-management" @submit.prevent="save">
    <h2>授权模式</h2>
    <label class="policy-choice"><input v-model="mode" type="radio" value="ALLOW" name="mode"><span><strong>默认允许</strong><small>允许使用此伙伴已接入的能力。</small></span></label>
    <label class="policy-choice"><input v-model="mode" type="radio" value="RESTRICTED" name="mode"><span><strong>部分限制</strong><small>为此伙伴选择允许使用的能力，关闭后由服务端阻止新操作。</small></span></label>
    <label v-if="mode==='RESTRICTED'" class="policy-capability"><input v-model="enabled" type="checkbox">{{narrative?'允许此伙伴对话及 NarrativePPT 工作台':'允许此伙伴调用模型生成文字'}}</label>
    <p class="studio-note">{{narrative?'限制覆盖此账号的PPT工作台和接口访问；已开始的操作不会被撤回。':'仅作用于此伙伴发起的新消息及任务；不取消运行中的任务，不影响独立技能及其他伙伴。'}}设置仅对当前账号生效。</p>
    <button class="studio-btn primary" type="submit" :disabled="busy">{{busy?'保存中…':'保存授权设置'}}</button><p v-if="notice" class="studio-note" role="status">{{notice}}</p>
   </form>
  </template><p v-else-if="!error" class="studio-note">正在读取伙伴详情…</p>
 </div>
</main>
</template>
