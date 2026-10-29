<script setup>
import { computed, ref } from 'vue'
import StudioIcon from './StudioIcon.vue'
const props = defineProps({ event: { type: Object, required: true } })
const open = ref(false)
const failed = computed(() => props.event.type === 'error' || props.event.ok === false)
// Closed logs must not stringify or create large hidden text nodes on input.
const detail = computed(() => {
  if (!open.value) return ''
  const event = props.event, value = event.arguments ?? event.output ?? event.error ?? event.result ?? event.message
  return typeof value === 'string' ? value : JSON.stringify(value, null, 2)
})
</script>
<template>
  <details class="coding-tool-event" :class="{ 'tool-error': failed }" @toggle="open = $event.target.open">
    <summary><StudioIcon :name="failed ? 'close' : event.type === 'tool_end' ? 'check' : 'notes'" :size="15" /><span>{{ event.type === 'error' ? event.message : `${event.type === 'tool_start' ? '调用' : event.ok === false ? '失败' : '完成'} ${event.toolName || '工具'}` }}</span></summary>
    <pre v-if="open">{{ detail }}</pre>
  </details>
</template>
