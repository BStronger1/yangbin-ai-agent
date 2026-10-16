<script setup>
import { computed } from 'vue'

const props = defineProps({ quality: Object, pending: Boolean })
const checks = computed(() => Array.isArray(props.quality?.checks) ? props.quality.checks.filter(Boolean) : [])
const findings = computed(() => Array.isArray(props.quality?.findings) ? props.quality.findings.filter(Boolean) : [])
const limitations = computed(() => Array.isArray(props.quality?.limitations) ? props.quality.limitations.filter(item => typeof item === 'string' && item.trim()) : [])
const warnings = computed(() => findings.value.filter(finding => finding.severity === 'warning').length)
const reportStatus = computed(() => ({ REVIEW_REQUIRED: '发现待核实事项', CHECKED_WITH_LIMITATIONS: '规则检查完成，仍有未核实信息' })[props.quality?.status] || '来源检查状态待确认')
const checkStatus = { SUPPORTED: '有对应依据', UNVERIFIED: '尚未核实', CONFLICT: '发现不一致' }
function count(value) { return Number.isInteger(value) && value >= 0 ? value : '未提供' }
function checkedTime(value) {
  if (!value) return '未提供'
  const time = new Date(value)
  return Number.isNaN(time.getTime()) ? '未提供' : time.toLocaleString('zh-CN', { hour12: false })
}
function sourceUrl(value) {
  if (typeof value !== 'string') return ''
  try {
    const url = new URL(value)
    return ['http:', 'https:'].includes(url.protocol) && !url.username && !url.password ? url.href : ''
  } catch { return '' }
}
function sourceName(value) {
  const url = sourceUrl(value)
  return url ? new URL(url).hostname : ''
}
function stopLabel(index) { return Number.isInteger(index) && index >= 0 ? `行程第 ${index + 1} 站` : '整体方案' }
</script>

<template>
  <section class="panel plan-quality" data-testid="plan-quality" aria-label="方案来源检查">
    <div class="quality-heading"><h2>方案来源检查</h2><span v-if="quality" class="quality-state" :class="{review:quality.status === 'REVIEW_REQUIRED'}">{{reportStatus}}</span></div>
    <template v-if="!quality">
      <p v-if="pending" class="quality-empty">来源检查尚未完成，当前方案仍是草案。</p>
      <p v-else class="quality-empty">历史方案未做来源检查</p>
      <p class="muted">没有检查报告不能视为已通过核验。地点、天气和费用等信息仍需出行前确认。</p>
    </template>
    <template v-else>
      <p class="muted">检查于 {{checkedTime(quality.checkedAt)}}<span v-if="quality.ruleVersion"> · 规则版本 {{quality.ruleVersion}}</span></p>
      <div class="quality-match"><strong>地点名称与候选引用对应：{{count(quality.matchedStops)}} / {{count(quality.totalStops)}} 站</strong><p class="muted">仅表示方案地点名称与候选引用对应，不代表价格、当天营业状态或其他描述已核实。</p></div>
      <div v-if="findings.length" class="quality-findings">
        <h3>需要留意的事项<span v-if="warnings" class="warning-count">{{warnings}} 项提醒</span></h3>
        <ul>
          <li v-for="(finding,index) in findings" :key="`${finding.code || 'notice'}-${index}`" :class="{warning:finding.severity === 'warning'}" :data-testid="`quality-finding-${finding.code || 'UNKNOWN'}`">
            <span class="finding-tag">{{finding.severity === 'warning' ? '待核实' : '说明'}}</span><p>{{finding.message || '该事项尚未提供详细说明，请出行前确认。'}}</p>
          </li>
        </ul>
      </div>
      <p v-else class="muted">报告未列出额外提醒；这不表示方案中的全部信息都已核实。</p>
      <details class="quality-checks" :open="quality.status === 'REVIEW_REQUIRED'">
        <summary>逐项检查与来源 · {{checks.length}} 项</summary>
        <article v-for="(check,index) in checks" :key="`${check.id || 'check'}-${index}`" class="quality-check">
          <div class="check-heading"><h3>{{check.label || '检查项'}}</h3><span class="check-state" :class="check.status">{{checkStatus[check.status] || '状态待确认'}}</span></div>
          <p class="muted">{{stopLabel(check.stopIndex)}}</p>
          <p>{{check.detail || '未提供检查说明，请结合原始来源确认。'}}</p>
          <a v-if="sourceUrl(check.sourceUrl)" :href="sourceUrl(check.sourceUrl)" target="_blank" rel="noopener noreferrer">查看对应来源 <span class="source-host">{{sourceName(check.sourceUrl)}}</span><span class="sr-only">（新窗口打开）</span></a>
          <p v-else-if="check.sourceUrl" class="muted">该来源链接不可用，请通过正规渠道核实。</p>
          <p v-else class="muted">未提供可打开的来源链接。</p>
        </article>
        <p v-if="!checks.length" class="muted empty-checks">本报告没有逐项检查记录，不能据此确认方案信息。</p>
      </details>
      <div class="quality-limits"><h3>检查范围与限制</h3><ul v-if="limitations.length"><li v-for="(text,index) in limitations" :key="index">{{text}}</li></ul><p v-else>检查仅覆盖报告列出的规则和已保存的来源，不代表对全部描述完成事实核验。</p><p class="muted">行程费用属于估算；实际价格、营业时间和交通耗时请在出行前再次确认。</p></div>
    </template>
  </section>
</template>

<style scoped>
.plan-quality{overflow-wrap:anywhere;min-width:0}.quality-heading{display:flex;align-items:flex-start;justify-content:space-between;gap:12px;flex-wrap:wrap;margin-bottom:14px}.quality-heading h2{margin:0}.quality-state{display:inline-block;background:#edf4f2;color:#3c6e61;font-size:12px;font-weight:600;padding:5px 9px;border-radius:6px;line-height:1.6}.quality-state.review{background:#fff2d9;color:#8a5e15}.quality-empty{font-weight:600;margin-bottom:8px}.quality-match{background:#f5f8f7;border-radius:9px;padding:14px;margin:16px 0}.quality-match strong{font-size:14px;color:#345a50}.quality-match p{margin-top:7px}.quality-findings h3{display:flex;align-items:center;flex-wrap:wrap;gap:9px}.warning-count{font-size:11px;font-weight:500;color:#8e651e;background:#fff3df;padding:3px 7px;border-radius:5px}.quality-findings ul{padding:0;list-style:none}.quality-findings li{display:flex;align-items:flex-start;gap:10px;background:#f4f7f9;border-left:3px solid #c7d8df;padding:12px;border-radius:5px;margin:9px 0}.quality-findings li.warning{background:#fffaef;border-left-color:#d9ae54}.quality-findings p{font-size:13px;margin:0}.finding-tag{flex-shrink:0;font-size:11px;color:#5d7480;padding-top:3px}.warning .finding-tag{color:#9b6c1c}.quality-checks{margin:20px 0}.quality-check{padding:16px 0;border-bottom:1px solid #e7eeea}.check-heading{display:flex;justify-content:space-between;align-items:flex-start;gap:10px;flex-wrap:wrap}.check-heading h3{margin:0 0 7px;font-size:14px}.check-state{font-size:11px;flex-shrink:0;padding:3px 7px;border-radius:5px;background:#eff2f4;color:#66747b}.check-state.SUPPORTED{background:#eaf4ef;color:#47715f}.check-state.UNVERIFIED{background:#fff3de;color:#946b25}.check-state.CONFLICT{background:#fff0ec;color:#a44d3a}.quality-check p{font-size:13px;margin:6px 0}.quality-check a{display:inline-block;color:#196f69;font-size:13px;line-height:1.8;margin-top:5px;text-decoration:underline;text-underline-offset:3px;max-width:100%}.source-host{font-size:11px;color:#657e76;margin-left:7px}.quality-limits{padding-top:4px}.quality-limits li,.quality-limits p{font-size:13px}.quality-limits p{margin-top:10px}.empty-checks{margin-top:14px}.sr-only{position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0}@media(max-width:600px){.quality-heading{flex-direction:column;gap:9px}.quality-state{max-width:100%}.quality-findings li{gap:7px;padding:10px}.source-host{display:block;margin-left:0}.check-heading{gap:6px}.quality-match{padding:12px}}
</style>
