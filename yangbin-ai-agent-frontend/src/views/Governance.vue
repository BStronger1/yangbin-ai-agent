<script setup>
import {ref,onMounted,onBeforeUnmount,watch} from 'vue'
import WorkspaceLayout from '../components/WorkspaceLayout.vue'
import {api,errorMessage} from '../workspace-api'
import {auth} from '../auth'
const summary=ref(null),events=ref([]),health=ref(null),error=ref(''),loading=ref(false),memo=ref(''),saved=ref(''),saving=ref(false)
let epoch=0,closed=false
const labels={RUNNING:'运行中',SUCCEEDED:'成功',FAILED:'失败',CANCELLED:'取消',UNKNOWN:'结果未知'}
const number=value=>value==null?'未知':Number(value).toLocaleString('zh-CN')
async function refresh(){const token=++epoch;loading.value=true;error.value='';const owner=auth.user?.id
 try{const values=await Promise.all([api.get('/ai/governance'),api.get('/ai/governance/events'),api.get('/ai/manus/memory'),auth.user?.role==='admin'?api.get('/ops/health'):Promise.resolve({data:null})]);if(closed||token!==epoch||owner!==auth.user?.id)return
 summary.value=values[0].data;events.value=values[1].data;memo.value=values[2].data.text;health.value=values[3].data
 }catch(e){if(token===epoch)error.value=errorMessage(e)}finally{if(token===epoch)loading.value=false}}
async function save(){const owner=auth.user?.id,token=epoch;saving.value=true;saved.value='';try{await api.put('/ai/manus/memory',{text:memo.value});if(!closed&&owner===auth.user?.id&&token===epoch)saved.value='已保存；下一轮主对话将使用此便签。'}catch(e){error.value=errorMessage(e)}finally{saving.value=false}}
watch(()=>auth.user?.id,()=>{epoch++;summary.value=null;events.value=[];health.value=null;memo.value='';saved.value='';error.value=''})
onMounted(refresh);onBeforeUnmount(()=>{closed=true;epoch++})
</script>
<template><WorkspaceLayout title="用量与运行状态" description="查看当前账号的调用额度、已知用量和最近执行状态。">
<div class="row"><button @click="refresh" :disabled="loading||saving">{{loading?'读取中…':'刷新状态'}}</button><span v-if="summary" class="muted">日账期 {{summary.day}} · {{summary.timezone}}</span></div><p v-if="error" role="alert" class="error">{{error}}</p>
<template v-if="summary"><div class="metrics-grid"><section class="panel"><h2>今日模型调用</h2><strong>{{number(summary.requests)}} / {{number(summary.limits.dailyRequests)}}</strong><p>受治理的模型请求计数</p></section><section class="panel"><h2>Token 预算占用</h2><strong>{{number(summary.budgetChargedTokens)}} / {{number(summary.limits.dailyTokens)}}</strong><p>已知用量 {{number(summary.knownTokens)}}；未知用量调用 {{number(summary.unknownUsageRequests)}}</p></section><section class="panel"><h2>正在执行</h2><strong>{{number(summary.active)}} / {{number(summary.limits.accountConcurrency)}}</strong><p>费用未知：尚未配置供应商价格，不能把 Token 额度当作金额预算。</p></section></div>
<p class="notice" role="status" v-if="summary.remainingTokens===0||summary.requests>=summary.limits.dailyRequests">今日额度已用完，新模型请求会被拒绝。已发送的请求可能仍由供应商计费。</p><p class="notice" v-else-if="summary.failures">今日存在 {{summary.failures}} 条失败、取消或结果未知记录。请根据调用编号定位，避免重复发送结果未明的任务。</p>
<p class="muted">每次调用按预留估算与已知用量的较大值占用预算，未知用量保留预留额度；实际费用与 Token 以供应商账单为准。{{summary.scope}}</p>
<section class="panel"><h2>最近调用</h2><p v-if="!events.length">今日暂无记录。</p><div class="event-table"><table v-if="events.length"><thead><tr><th>状态</th><th>模型</th><th>输入 / 输出 Token</th><th>耗时</th><th>调用编号</th></tr></thead><tbody><tr v-for="item in events" :key="item.traceId"><td>{{labels[item.status]||item.status}}</td><td>{{item.provider}} / {{item.model}}</td><td>{{number(item.inputTokens)}} / {{number(item.outputTokens)}}</td><td>{{number(item.durationMs)}} ms</td><td><code>{{item.traceId}}</code><small v-if="item.requestId">请求 {{item.requestId}}</small></td></tr></tbody></table></div></section>
</template>
<section class="panel memo"><h2>主对话参考便签</h2><p class="muted">由你明确保存、修改或清空；跨主对话使用。它不会授予工具权限，也不会自动替你执行任务。</p><textarea aria-label="主对话参考便签" v-model="memo" maxlength="4000" rows="6" placeholder="例如：回复使用中文；项目使用 Java 21；交付时列出实际执行的验证。"></textarea><div class="row"><button :disabled="saving||loading" @click="save">{{saving?'保存中…':'保存便签'}}</button><button class="secondary" :disabled="saving||loading" @click="memo='';save()">清空便签</button><span role="status">{{saved}}</span></div></section>
<section v-if="health" class="panel"><h2>管理员网关状态</h2><p class="muted">以下只证明网关与部署配置状态，不代表模型、工具或全部依赖健康。</p><pre data-testid="gateway-health">{{JSON.stringify(health,null,2)}}</pre></section>
</WorkspaceLayout></template>
<style scoped>.metrics-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:16px;margin:20px 0}.panel{min-width:0;margin-bottom:20px}.panel h2{font-size:17px;margin-bottom:12px}.panel strong{font-size:23px}.panel p{line-height:1.7;margin:10px 0}.row{display:flex;gap:12px;align-items:center;flex-wrap:wrap}.muted,small{color:#687d8a;font-size:13px}small{display:block;margin-top:5px}.notice{background:#fff4da;padding:16px;border-radius:12px;margin-bottom:16px}.error{color:#a92e2e}.event-table{overflow-x:auto}table{border-collapse:collapse;width:100%;font-size:13px}td,th{text-align:left;padding:12px;border-bottom:1px solid #e6eaef;vertical-align:top}code{font-size:11px}textarea{width:100%;margin:12px 0;box-sizing:border-box}pre{white-space:pre-wrap;overflow-wrap:anywhere;font-size:12px}@media(max-width:800px){.metrics-grid{grid-template-columns:1fr}}
</style>
