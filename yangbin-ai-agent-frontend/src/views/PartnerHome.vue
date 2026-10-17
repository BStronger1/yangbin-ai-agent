<script setup>
import {ref,watch,onBeforeUnmount,computed} from 'vue'
import {useRoute} from 'vue-router'
import {api,errorMessage} from '../workspace-api'
import {auth} from '../auth'
import PartnerIdentity from '../components/PartnerIdentity.vue'

import PartnerChat from '../components/PartnerChat.vue'
import '../studio.css'
import '../partner.css'
const route=useRoute(), detail=ref(null), partners=ref([]), error=ref(''), loading=ref(true), section=ref('tasks'), projects=ref([]), skills=ref([])
const narrative=computed(()=>detail.value?.partner.workspace==='narrative-ppt')
let epoch=0,controller
async function load(){
 const ticket=++epoch,owner=auth.user?.id;controller?.abort();controller=new AbortController()
 loading.value=true;error.value='';detail.value=null;projects.value=[];partners.value=[];section.value='tasks'
 const opts={signal:controller.signal}
 try{
  const id=encodeURIComponent(route.params.partnerId)
  await api.post('/ai/work/partners/'+id+'/summon',{},opts)
  const [d,c,s]=await Promise.all([api.get('/ai/work/partners/'+id,opts),api.get('/ai/work/catalog',opts),api.get('/ai/work/partners',opts)])
  if(ticket!==epoch||owner!==auth.user?.id)return
  detail.value=d.data;skills.value=c.data.skills;partners.value=c.data.partners.filter(p=>s.data.includes(p.id))
  if(narrative.value&&d.data.policy.enabled){const {data}=await api.get('/ppt/api/v1/projects',{...opts,baseURL:'/'});if(ticket===epoch&&owner===auth.user?.id)projects.value=data}
 }catch(e){if(ticket===epoch&&e.code!=='ERR_CANCELED')error.value=errorMessage(e)}finally{if(ticket===epoch)loading.value=false}
}
watch([()=>route.params.partnerId,()=>auth.user?.id],load,{immediate:true})
onBeforeUnmount(()=>{epoch++;controller?.abort()})
</script>
<template>
 <PartnerChat v-if="detail" :key="detail.partner.id" :partner="detail.partner" :partners="partners" :skills="skills" :enabled="detail.policy.enabled" :projects="projects"/>
 <main v-else class="partner-loading"><p v-if="error" role="alert">{{error}} <button @click="load">重新加载</button></p><p v-else>正在打开伙伴…</p></main>
</template>
<style scoped>.partner-loading{height:100dvh;display:grid;place-items:center;background:white;color:#8b94a2}</style>
