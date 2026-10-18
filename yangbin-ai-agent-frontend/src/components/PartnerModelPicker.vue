<script setup>
import {ref,watch,onBeforeUnmount} from 'vue'
import {auth} from '../auth'
import {modelApi} from '../api/models'
import ModelManager from './ModelManager.vue'
const props=defineProps({partnerId:String,modelProfileId:String,disabled:Boolean})
const emit=defineEmits(['update:modelProfileId','ready'])
const catalog=ref({profiles:[],providers:[],defaultModelId:'system'}),loading=ref(true),error=ref(''),manager=ref(false)
let generation=0
const key=()=>`partner-model:${auth.user?.id||auth.user?.username}:${props.partnerId}`
function choose(id){emit('update:modelProfileId',id);try{sessionStorage.setItem(key(),id)}catch{}}
async function load(preferred){const current=++generation;loading.value=true;error.value='';emit('ready',false)
 try{const result=await modelApi.list();if(current!==generation)return;catalog.value=result;let saved='';try{saved=sessionStorage.getItem(key())||''}catch{}const valid=id=>id==='system'||result.profiles.some(p=>p.id===id);const id=preferred||props.modelProfileId||saved||result.defaultModelId||'system';choose(valid(id)?id:(valid(result.defaultModelId)?result.defaultModelId:'system'));emit('ready',true)}
 catch{if(current===generation)error.value='模型配置未能加载'}finally{if(current===generation)loading.value=false}}
watch([()=>props.partnerId,()=>auth.user?.id],()=>{manager.value=false;load()},{immediate:true})
onBeforeUnmount(()=>{generation++})
</script>
<template>
 <div class="partner-model-control" aria-live="polite">
  <select :value="modelProfileId" @change="choose($event.target.value)" :disabled="disabled||loading||!!error" aria-label="伙伴使用的模型" data-testid="partner-model-select"><option value="system">{{loading?'加载模型…':'系统默认模型'}}</option><option v-for="p in catalog.profiles" :key="p.id" :value="p.id">{{p.name}} · {{p.model}}</option></select>
  <button type="button" :disabled="disabled||loading||!!error" @click="manager=true" data-testid="partner-model-settings">模型设置</button>
  <button v-if="error" type="button" @click="load()" :disabled="disabled">{{error}} · 重试</button>
 </div>
 <Teleport to="body"><ModelManager v-if="manager" :catalog="catalog" @close="manager=false" @changed="load"/></Teleport>
</template>
<style scoped>
.partner-model-control{display:flex;align-items:center;gap:6px;min-width:0}.partner-model-control select{min-width:80px;max-width:210px;border:1px solid #e5e9f1;border-radius:8px;background:#f8faff;padding:7px;color:#415a7a;font-size:12px}.partner-model-control button{border:0;background:transparent;color:#477ac5;font-size:12px;white-space:nowrap;padding:6px}.partner-model-control button:disabled{opacity:.5}@media(max-width:650px){.partner-model-control select{max-width:140px}.partner-model-control button{font-size:11px}}
</style>
