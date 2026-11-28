<script setup>
defineProps({ modelValue: { type: String, default: 'balanced' }, disabled: Boolean })
const emit = defineEmits(['update:modelValue'])
const budgets = [
  { value: 'economy', label: '省钱', hint: 'jtau=0.0 · 约省 61%' },
  { value: 'balanced', label: '均衡', hint: 'jtau=0.50 · 约省 57%' },
  { value: 'quality', label: '高质量', hint: 'jtau=0.85 · 约省 32%' },
]
</script>

<template>
  <div class="router-budget" data-testid="router-budget-picker" role="group" aria-label="智能路由预算策略">
    <button v-for="budget in budgets" :key="budget.value" type="button" :data-budget-mode="budget.value"
      :title="budget.hint" :aria-pressed="modelValue === budget.value" :disabled="disabled"
      @click="emit('update:modelValue', budget.value)">{{ budget.label }}</button>
  </div>
</template>

<style scoped>
.router-budget{display:inline-flex;gap:3px;max-width:100%;padding:3px;border:1px solid var(--yb-border);border-radius:999px;background:var(--yb-bg-subtle)}
.router-budget button{border:0;border-radius:999px;padding:6px 11px;background:transparent;color:var(--yb-ink-muted);font:inherit;font-size:12px;white-space:nowrap;cursor:pointer;transition:background var(--yb-motion),color var(--yb-motion),box-shadow var(--yb-motion)}
.router-budget button:hover:not(:disabled){color:var(--yb-accent);background:var(--yb-accent-soft)}
.router-budget button[aria-pressed="true"]{background:var(--yb-bg-surface);color:var(--yb-accent);box-shadow:0 1px 4px #0f172a12;font-weight:600}
.router-budget button:focus-visible{outline:2px solid var(--yb-accent);outline-offset:2px}.router-budget button:disabled{opacity:.55;cursor:default}
</style>
