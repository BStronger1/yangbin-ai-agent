<script setup>
import {ref,computed,watch,onBeforeUnmount} from 'vue'
import {auth} from '../auth'
import {modelApi} from '../api/models'
import ReasoningEffort from './ReasoningEffort.vue'
const props=defineProps({partnerId:String,modelProfileId:String,reasoningEffort:{type:String,default:''},disabled:Boolean})
const emit=defineEmits(['update:modelProfileId','update:reasoningEffort','ready'])
const catalog=ref({profiles:[],systemEnabled:false}),loading=ref(true),error=ref('')
let generation=0
const key=()=>`partner-model:${auth.user?.id||auth.user?.username}:${props.partnerId}`
const valid=id=>id==='system'?catalog.value.systemEnabled!==false:catalog.value.profiles.some(p=>p.id===id)
const levels=computed(()=>catalog.value.profiles.find(p=>p.id===props.modelProfileId)?.reasoningEfforts||[])
function effort(value){emit('update:reasoningEffort',value);try{sessionStorage.setItem(key()+':effort:'+props.modelProfileId,value)}catch{}}
function restoreEffort(id){let value='';try{value=sessionStorage.getItem(key()+':effort:'+id)||''}catch{}const allowed=catalog.value.profiles.find(p=>p.id===id)?.reasoningEfforts||[];emit('update:reasoningEffort',allowed.includes(value)?value:'')}
function choose(id){emit('update:modelProfileId',id);try{sessionStorage.setItem(key(),id)}catch{}restoreEffort(id);emit('ready',valid(id)&&!loading.value&&!error.value)}
async function load(){const current=++generation;loading.value=true;error.value='';emit('ready',false)
 try{const result=await modelApi.list();if(current!==generation)return;catalog.value=result;let saved='';try{saved=sessionStorage.getItem(key())||''}catch{}const id=props.modelProfileId||saved||result.defaultModelId||'';loading.value=false;choose(valid(id)?id:'')}
 catch{if(current===generation)error.value='模型配置未能加载'}finally{if(current===generation)loading.value=false}}
watch([()=>props.partnerId,()=>auth.user?.id],()=>{emit('update:modelProfileId','');emit('update:reasoningEffort','');load()},{immediate:true})
onBeforeUnmount(()=>{generation++})
</script>
<template>
 <div class="partner-model-control" aria-live="polite">
  <select :value="modelProfileId" @change="choose($event.target.value)" :disabled="disabled||loading||!!error" aria-label="使用的模型" data-testid="partner-model-select"><option v-if="!modelProfileId||loading" value="" disabled>{{loading?'加载模型…':'请重新选择模型'}}</option><option v-if="catalog.systemEnabled!==false" value="system">系统默认模型</option><option v-for="p in catalog.profiles" :key="p.id" :value="p.id">{{p.name}} · {{p.model}}</option></select>
  <ReasoningEffort :model-value="reasoningEffort" :levels="levels" :disabled="disabled||loading||!modelProfileId" @update:model-value="effort"/>
  <RouterLink to="/settings" data-testid="partner-model-settings">设置</RouterLink>
  <button v-if="error" type="button" @click="load()" :disabled="disabled">{{error}} · 重试</button>
  <small v-else-if="!loading&&!modelProfileId">模型未启用，请选择或到设置勾选。</small>
 </div>
</template>
<style scoped>
.partner-model-control{display:flex;align-items:center;gap:6px;min-width:0;flex-wrap:wrap}.partner-model-control>select{min-width:80px;max-width:210px;border:1px solid #e5e9f1;border-radius:8px;background:#f8faff;padding:7px;color:#415a7a;font-size:12px}.partner-model-control button,.partner-model-control a{border:0;background:transparent;color:#477ac5;font-size:12px;white-space:nowrap;padding:6px}.partner-model-control small{color:#9e5c25;font-size:11px}.partner-model-control button:disabled{opacity:.5}@media(max-width:650px){.partner-model-control>select{max-width:170px}.partner-model-control button{font-size:11px}}
</style>
