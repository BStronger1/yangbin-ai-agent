<script setup>
import { computed, ref } from 'vue'
const props = defineProps({ decision: { type: Object, default: null } })
const expanded = ref(false)
const intents = {
  coding_debug_repair: '代码调试与修复', general_chat_qa: '通用对话问答',
  knowledge_rag_query: '知识库检索', local_file_workspace_op: '本地工作区操作',
  memory_manage_recall: '长期记忆管理', out_of_scope_or_unsafe: '越界与安全拒答',
  task_plan_clarify: '任务规划与澄清', tool_mcp_a2a_orchestration: '工具与多端编排',
  writing_blog_edit: '写作与博客编辑',
}
const tiers = [
  { tier: 'local', key: 'Qwen/Qwen3-4B', label: 'Qwen/Qwen3-4B', short: 'Qwen3-4B' },
  { tier: 'flash', key: 'deepseek-v4.1-flash-1017', label: 'DeepSeek V4.1 Flash', short: 'DeepSeek Flash' },
  { tier: 'pro', key: 'deepseek-v4-pro-0813', label: 'DeepSeek V4 Pro', short: 'DeepSeek Pro' },
  { tier: 'abstain', key: 'abstain', label: 'Abstain', short: '已安全回退' },
]
const budgets = { economy: '省钱', balanced: '均衡', quality: '高质量' }
const reasons = { timeout: '路由响应超时', circuit_open: '路由引擎正在冷却', router_abstain: '路由器选择谨慎回退', tier_unconfigured: '目标档位暂未配置', network_error: '路由连接暂不可用', invalid_response: '路由响应暂不可用', invalid_schema: '路由响应暂不可用' }
const fallback = computed(() => Boolean(props.decision?.fallbackReason && props.decision.fallbackReason !== 'autoregressive_fallback') || props.decision?.tier === 'abstain')
const tier = computed(() => fallback.value ? 'fallback' : tiers.find(item => item.tier === props.decision?.tier || item.key === props.decision?.chosenModel)?.tier || 'fallback')
const tierLabel = computed(() => fallback.value ? '已安全回退' : tiers.find(item => item.tier === tier.value)?.short || '默认模型')
const intent = computed(() => intents[props.decision?.intent] || props.decision?.intent || '未识别意图')
const numeric = value => value != null && Number.isFinite(Number(value))
const decimal = value => numeric(value) ? Number(value).toFixed(1) : '—'
const percent = value => numeric(value) ? (Math.min(1, Math.max(0, Number(value))) * 100).toFixed(1) + '%' : '—'
const candidates = computed(() => tiers.map(item => {
  const value = props.decision?.candidateProbs?.[item.key] ?? props.decision?.candidateProbs?.[item.tier]
  return { ...item, value: numeric(value) ? Math.min(1, Math.max(0, Number(value))) : null,
    chosen: item.key === props.decision?.chosenModel || item.tier === props.decision?.tier }
}))
</script>

<template>
  <details v-if="decision?.enabled" class="smart-router" :class="'router-' + tier" @toggle="expanded = $event.target.open">
    <summary data-testid="smart-router-badge" class="router-badge" :aria-expanded="expanded">
      <span class="router-brand">✦ 64M 智能路由</span><span class="router-intent">{{ intent }}</span><span class="router-arrow" aria-hidden="true">→</span>
      <span class="router-tier">{{ tierLabel }}</span><span class="router-timing">{{ decimal(decision.routerLatencyMs) }} ms</span>
      <span v-if="decision.estimatedSavingPct > 0 && !fallback" class="router-saving">省 {{ decimal(decision.estimatedSavingPct) }}%</span>
      <span v-if="decision.shadowOnly" class="router-shadow">影子观测</span><span class="router-chevron" aria-hidden="true">⌄</span>
    </summary>
    <section v-if="expanded" class="router-panel" data-testid="smart-router-panel" aria-label="智能路由决策洞察">
      <header><span>决策洞察</span><small>每一轮选择，都有迹可循</small></header>
      <div class="router-metrics">
        <div><span class="metric-label">识别意图</span><strong>{{ intent }}</strong><small>{{ decision.intent || '未返回意图' }}</small></div>
        <div><span class="metric-label">执行档位</span><strong>{{ tierLabel }} <em v-if="decision.promoted">低置信度升档</em></strong><small>{{ decision.rawModel || '默认模型' }} → {{ decision.chosenModel || '默认云端模型' }}</small></div>
        <div><span class="metric-label">路由耗时 &amp; 联合置信度</span><strong>{{ decimal(decision.routerLatencyMs) }} ms <span class="metric-divider">/</span> {{ percent(decision.jointConfidence) }}</strong><small>路由置信度 {{ percent(decision.routeConfidence ?? decision.confidence) }}</small></div>
        <div><span class="metric-label">预算策略与预估降本</span><strong>{{ budgets[decision.budgetMode] || '均衡' }} · {{ fallback ? '—' : decimal(decision.estimatedSavingPct) + '%' }}</strong><small>{{ decision.budgetMode || 'balanced' }} · 预估值，实际费用以供应商账单为准</small></div>
      </div>
      <div class="router-distribution"><h3>候选概率分布 <small>最终选择以执行档位为准</small></h3>
        <div v-for="candidate in candidates" :key="candidate.key" class="router-candidate" :class="{chosen: candidate.chosen}" data-testid="router-candidate" :data-tier="candidate.tier">
          <div class="candidate-label"><span>{{ candidate.label }} <small v-if="candidate.chosen">{{ decision.shadowOnly ? '观测选择' : '最终选择' }}</small></span><span>{{ percent(candidate.value) }}</span></div>
          <div class="candidate-track" role="meter" :aria-label="candidate.label + ' 候选概率'" aria-valuemin="0" aria-valuemax="100" :aria-valuenow="candidate.value == null ? 0 : candidate.value * 100" :aria-valuetext="percent(candidate.value)"><span :style="{ width: (candidate.value || 0) * 100 + '%' }"></span></div>
        </div>
      </div>
      <p v-if="decision.shadowOnly" class="router-note">影子观测：仅记录路由建议，本轮仍使用默认模型，预估降本尚未实际应用。</p>
      <p v-if="fallback" class="router-note" data-testid="router-fallback">{{ reasons[decision.fallbackReason] || '路由暂不可用' }}（{{ decision.fallbackReason || 'abstain' }}）。已静默回退至默认云端模型，保障体验不中断。</p>
      <p v-else-if="decision.fallbackReason === 'autoregressive_fallback'" class="router-note">快速推理暂不可用，已切换常规推理路径，保留本轮路由选择。</p>
    </section>
  </details>
</template>

<style scoped>
.smart-router{--tier-bg:var(--yb-router-fallback-bg);--tier-ink:var(--yb-router-fallback-ink);--tier-border:var(--yb-router-fallback-border);max-width:100%;margin:12px 0 18px;color:var(--yb-ink-secondary);font-size:12px}
.router-local{--tier-bg:var(--yb-router-local-bg);--tier-ink:var(--yb-router-local-ink);--tier-border:var(--yb-router-local-border)}.router-flash{--tier-bg:var(--yb-router-flash-bg);--tier-ink:var(--yb-router-flash-ink);--tier-border:var(--yb-router-flash-border)}.router-pro{--tier-bg:var(--yb-router-pro-bg);--tier-ink:var(--yb-router-pro-ink);--tier-border:var(--yb-router-pro-border)}
.router-badge{display:flex;width:fit-content;max-width:100%;box-sizing:border-box;align-items:center;flex-wrap:wrap;gap:7px;padding:8px 11px;list-style:none;border:1px solid var(--yb-border);border-radius:16px;background:var(--yb-bg-surface);cursor:pointer;transition:border-color var(--yb-motion),box-shadow var(--yb-motion)}
.router-badge::-webkit-details-marker{display:none}.router-badge:hover{border-color:var(--yb-border-strong);box-shadow:0 2px 8px #0f172a08}.router-badge:focus-visible{outline:3px solid var(--yb-accent-ring);outline-offset:2px}.router-brand{font-weight:650;color:var(--yb-ink);white-space:nowrap}.router-intent{overflow-wrap:anywhere}.router-arrow,.router-chevron{color:var(--yb-ink-muted)}.router-chevron{margin-left:2px;transition:transform var(--yb-motion)}.smart-router[open] .router-chevron{transform:rotate(180deg)}
.router-tier{padding:3px 7px;border:1px solid var(--tier-border);border-radius:999px;color:var(--tier-ink);background:var(--tier-bg);white-space:nowrap;font-weight:600}.router-timing,.router-saving{font-variant-numeric:tabular-nums;white-space:nowrap}.router-saving{color:var(--yb-router-local-ink)}.router-shadow{border-radius:999px;padding:3px 6px;background:var(--yb-bg-subtle);color:var(--yb-ink-muted);white-space:nowrap}
.router-panel{margin-top:9px;padding:18px;border:1px solid var(--yb-border);border-radius:16px;background:var(--yb-bg-surface);box-shadow:0 8px 24px #0f172a06;overflow:hidden}.router-panel header{display:flex;align-items:baseline;flex-wrap:wrap;gap:10px;margin-bottom:15px}.router-panel header>span{font-size:14px;font-weight:650;color:var(--yb-ink)}.router-panel small{font-size:11px;color:var(--yb-ink-muted);line-height:1.6;overflow-wrap:anywhere}.router-metrics{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px}.router-metrics>div{padding:13px;border:1px solid var(--yb-border);border-radius:12px;background:var(--yb-bg-subtle);min-width:0}.metric-label,.router-metrics strong,.router-metrics small{display:block}.metric-label{color:var(--yb-ink-muted);margin-bottom:7px}.router-metrics strong{font-size:13px;font-weight:600;line-height:1.6;color:var(--yb-ink);overflow-wrap:anywhere;font-variant-numeric:tabular-nums}.router-metrics em{display:inline-block;font-size:10px;color:var(--yb-router-pro-ink);font-style:normal;background:var(--yb-router-pro-bg);padding:1px 5px;border-radius:4px}.metric-divider{margin:0 3px;color:var(--yb-border-strong)}.router-metrics small{margin-top:4px}.router-distribution{margin-top:18px}.router-distribution h3{font-size:12px;font-weight:600;margin:0 0 12px;display:flex;justify-content:space-between;flex-wrap:wrap;gap:6px;color:var(--yb-ink)}.router-distribution h3 small{font-weight:400}.router-candidate+.router-candidate{margin-top:11px}.candidate-label{display:flex;justify-content:space-between;gap:10px;margin-bottom:5px;font-variant-numeric:tabular-nums}.candidate-label>span:first-child{min-width:0;overflow-wrap:anywhere}.candidate-label small{margin-left:5px;color:var(--yb-accent)}.candidate-track{height:5px;border-radius:999px;background:var(--yb-bg-subtle);overflow:hidden}.candidate-track>span{display:block;height:100%;border-radius:inherit;background:var(--yb-border-strong)}.chosen .candidate-track>span{background:var(--yb-accent)}.router-note{margin:14px 0 0;padding:11px 13px;line-height:1.7;border-radius:8px;background:var(--yb-bg-subtle);color:var(--yb-ink-secondary);overflow-wrap:anywhere}
@media(max-width:540px){.router-panel{padding:13px}.router-metrics{grid-template-columns:1fr}.router-badge{gap:6px;font-size:11px}.router-metrics>div{padding:11px}}
</style>
