<script setup>
import {onMounted,ref} from 'vue'
import {codingApi} from '../api/coding'
const props=defineProps({projectId:String});const emit=defineEmits(['ready'])
const report=ref(null),busy=ref(false),error=ref('')
const labels={AFTER_MATCH:'当前内容与预期修改一致',BEFORE_MATCH:'当前内容与修改前一致（不证明从未执行）',DIVERGED:'当前内容与修改前后均不一致',UNVERIFIABLE:'无法安全核对文件',NOOP_CONTENT_MATCH:'修改前后内容相同，无法区分是否执行'}
async function refresh(){busy.value=true;error.value='';emit('ready',false);try{report.value=await codingApi.recovery(props.projectId);emit('ready',!report.value.required||report.value.accepted)}catch{error.value='恢复记录读取失败，请稍后重新核对。'}finally{busy.value=false}}
async function accept(){busy.value=true;error.value='';try{report.value=await codingApi.acceptRecovery(props.projectId,report.value.token);emit('ready',report.value.accepted)}catch{error.value='文件状态已变化或旧进程未退出，请重新核对。';emit('ready',false)}finally{busy.value=false}}
onMounted(refresh)
</script>
<template>
  <section class="coding-recovery" role="status" data-testid="coding-recovery">
    <strong>上轮任务需要核对文件</strong><p>{{report?.message || '正在读取操作记录…'}}</p>
    <ul v-if="report?.operations.length"><li v-for="op in report.operations" :key="op.sessionId+op.turnId+op.operationId"><code>{{op.path}}</code>：{{labels[op.observation]}}；{{op.state==='APPLIED'?'有写入完成记录':'仅有执行前记录，回执未确认'}}</li></ul>
    <p v-if="report && !report.operations.length">没有可用于证明写入结果的工具记录，请检查项目文件。命令及外部工具不在文件日志保证范围内。</p>
    <p v-for="message in report?.errors" :key="message">{{message}}</p><p v-if="error" role="alert">{{error}}</p>
    <p v-if="report?.accepted">已接受当前文件状态。下一轮将作为新任务执行，不会续跑旧工具调用。</p>
    <button type="button" :disabled="busy" @click="refresh">重新核对</button>
    <button v-if="report?.required && !report.accepted" type="button" :disabled="busy || !report.canAccept" @click="accept" data-testid="accept-recovery">我已检查文件，接受当前状态</button>
  </section>
</template>
<style scoped>.coding-recovery{border:1px solid #d8dfe8;border-radius:12px;background:#f7f9fc;padding:14px;margin:10px 0;font-size:13px;line-height:1.7;max-height:260px;overflow:auto}.coding-recovery button{padding:7px 12px;margin:5px;border:1px solid #bcc9da;border-radius:7px;background:white}.coding-recovery li{overflow-wrap:anywhere}</style>
