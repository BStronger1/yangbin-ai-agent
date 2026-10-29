<script setup>
import CodingToolEvent from './CodingToolEvent.vue'
import CodingTeam from './CodingTeam.vue'
import ReplyMarkdown from './ReplyMarkdown.vue'
defineProps({turn:Object,sessionId:String,sessionStatus:String,statusText:String,modelText:String})
defineEmits(['updated'])
</script>
<template>
  <article class="coding-turn" :data-turn="turn.id">
    <div class="coding-user-message">{{ turn.prompt }}</div>
    <div class="coding-agent-label"><span class="agent-mini">✦</span> Coding Agent <span class="turn-model" :title="modelText">{{ modelText }}</span><small>{{ statusText }}</small></div>
    <div v-if="turn.events.length" class="coding-tool-events"><CodingToolEvent v-for="event in turn.events" :key="event.seq" :event="event" /></div>
    <CodingTeam :session-id="sessionId" :turn-id="turn.id" :events="turn.teamEvents" :session-status="sessionStatus" @updated="$emit('updated', $event)" />
    <ReplyMarkdown v-if="turn.text" :text="turn.text" />
    <p v-else-if="['RUNNING','QUEUED'].includes(String(turn.status).toUpperCase())" class="coding-thinking"><span></span>正在处理任务…</p>
  </article>
</template>
