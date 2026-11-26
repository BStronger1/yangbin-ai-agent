<script setup>
defineProps({ evidence: { type: Object, required: true } })

const labels = {
  UNVERIFIED: '尚未验证',
  FAILED: '目标测试失败',
  UNSTABLE: '同条件复测结果不稳定',
  TEST_CHANGED: '测试定义已改变，需独立复核',
  PASSED: '目标测试通过',
  NO_TESTS: '未执行任何测试',
  BLOCKED: '验证受阻',
  STALE: '代码已变化，需重新验证',
}
</script>

<template>
  <section class="repair-verification" aria-label="修复验证" aria-live="polite">
    <strong>修复验证 · {{ evidence.status==='PASSED' && evidence.scope==='observed_tests' ? '已执行的测试通过' : labels[evidence.status] || '状态未知' }}</strong>
    <ul v-if="evidence.obligations?.length" aria-label="各项验收结果"><li v-for="item in evidence.obligations" :key="[item.runner,item.target,item.role].join(':')"><code>{{item.target}}</code> · {{labels[item.status] || item.status}}</li></ul>
    <dl>
      <template v-if="Number.isInteger(evidence.revision)">
        <dt>本轮修改次数</dt><dd>{{ evidence.revision }}</dd>
      </template>
      <template v-if="evidence.target">
        <dt>验证目标</dt><dd><code>{{ evidence.runner }} {{ evidence.target }}</code></dd>
      </template>
      <template v-if="Number.isInteger(evidence.testsRun)">
        <dt>执行用例</dt><dd>{{ evidence.testsRun }}</dd>
      </template>
      <template v-if="Number.isInteger(evidence.exitCode)">
        <dt>退出码</dt><dd>{{ evidence.exitCode }}</dd>
      </template>
      <template v-if="evidence.baselineFailed || evidence.status === 'PASSED'">
        <dt>基线复现</dt><dd>{{ evidence.baselineFailed ? '已记录目标测试失败' : '无失败记录' }}</dd>
      </template>
      <template v-if="evidence.snapshotHash">
        <dt>测试快照</dt><dd><code>{{ evidence.snapshotHash }}</code></dd>
      </template>
    </dl>
  </section>
</template>

<style scoped>
.repair-verification { margin: 14px 0; border-left: 3px solid #708198; padding: 4px 12px; color: #475569; font-size: 13px; }
.repair-verification dl { display: grid; grid-template-columns: max-content minmax(0,1fr); gap: 3px 12px; margin: 6px 0 0; line-height: 1.5; }
.repair-verification dt { color: #536477; }
.repair-verification dd { margin: 0; overflow-wrap: anywhere; }
</style>
