<script setup>
import {ref,onMounted} from 'vue'
const props=defineProps({skill:Object,draft:String})
const emit=defineEmits(['close','apply'])
const dialog=ref(null),values=ref({}),error=ref('')
onMounted(()=>dialog.value.showModal())
function apply(){
 const fields=props.skill.inputs.map((label,i)=>values.value[i]?.trim()?label+'：\n'+values.value[i].trim():'').filter(Boolean)
 if(!fields.length){error.value='至少补充一项信息，或关闭后直接输入需求。';return}
 const text=[props.draft?.trim()||'请完成'+props.skill.name,...fields].filter(Boolean).join('\n\n')
 if(text.length>12000){error.value='合并后超过12000字，请精简内容或通过附件提供材料。';return}
 emit('apply',text)
}
</script>
<template>
 <dialog ref="dialog" class="skill-brief" aria-labelledby="brief-title" @close="emit('close')" @click="e=>{if(e.target===dialog)dialog.close()}">
  <form @submit.prevent="apply">
   <header><h2 id="brief-title">{{skill.name}} · 需求引导</h2><button type="button" aria-label="关闭需求引导" @click="dialog.close()">×</button></header>
   <p>按已有信息填写，未知项可留空。生成的需求会放入输入框，确认后再发送。</p>
   <label v-for="(label,i) in skill.inputs" :key="label">{{label}}<textarea v-model="values[i]" :aria-label="label" :data-testid="'brief-field-'+i" maxlength="3000" rows="3" placeholder="填写已知信息，不需要编造缺失材料"></textarea></label>
   <details><summary>交付核对标准</summary><ul><li v-for="check in skill.checks" :key="check">{{check}}</li></ul></details>
   <p v-if="error" role="alert">{{error}}</p>
   <footer><button type="button" @click="dialog.close()">取消</button><button type="submit" class="primary">填入需求</button></footer>
  </form>
 </dialog>
</template>
<style scoped>
.skill-brief{margin:auto;font-family:inherit;width:min(620px,calc(100vw - 32px));max-height:calc(100dvh - 48px);padding:24px;border:1px solid #dfe6f1;border-radius:18px;color:#34445d;box-sizing:border-box;overflow:auto}.skill-brief::backdrop{background:#17223b66}.skill-brief header,.skill-brief footer{display:flex;align-items:center;justify-content:space-between;gap:12px}.skill-brief h2{font-size:18px;margin:0}.skill-brief p,.skill-brief li{font-size:13px;line-height:1.7}.skill-brief label{display:block;font-size:13px;margin:18px 0}.skill-brief textarea{display:block;box-sizing:border-box;width:100%;margin-top:7px;padding:10px;font:inherit;line-height:1.6;border:1px solid #d5deeb;border-radius:8px;resize:vertical}.skill-brief summary{cursor:pointer;font-size:13px}.skill-brief footer{justify-content:flex-end;margin-top:20px}.skill-brief button{border:1px solid #d5deeb;background:white;color:#415a7e;border-radius:8px;padding:8px 14px;cursor:pointer}.skill-brief .primary{color:white;background:#456ee2}.skill-brief header button{font-size:20px;padding:2px 10px}.skill-brief [role=alert]{color:#b44343}
</style>
