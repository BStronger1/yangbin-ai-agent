<script setup>
defineProps({modelValue:{type:String,default:''},levels:{type:Array,default:()=>[]},disabled:Boolean})
const emit=defineEmits(['update:modelValue'])
const labels={none:'不启用推理',off:'不启用推理',minimal:'最低',low:'低',medium:'中',high:'高',xhigh:'更高',max:'最高',ultra:'极高'}
</script>
<template><label class="reasoning-control"><span>推理强度</span><select v-if="levels.length" :value="modelValue" :disabled="disabled" aria-label="推理强度" data-testid="reasoning-effort" @change="emit('update:modelValue',$event.target.value)"><option value="">服务默认</option><option v-for="level in levels" :key="level" :value="level">{{labels[level]||level}}</option></select><small v-else data-testid="reasoning-unavailable">此模型未提供可调强度</small></label></template>
<style scoped>.reasoning-control{display:flex;align-items:center;gap:5px;font-size:11px;color:#6d7886;max-width:100%;flex-wrap:wrap}.reasoning-control select{background:#f8faff;color:#415a7a;border:1px solid #e5e9f1;border-radius:8px;padding:7px;max-width:110px;font-size:12px}.reasoning-control small{font-size:10px}</style>
