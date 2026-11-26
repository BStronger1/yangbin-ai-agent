<script setup>
import CodingToolEvent from './CodingToolEvent.vue'
import CodingTeam from './CodingTeam.vue'
import ReplyMarkdown from './ReplyMarkdown.vue'
import ServerCandidate from './ServerCandidate.vue'
import CodingVerification from './CodingVerification.vue'
defineProps({turn:Object,sessionId:String,sessionStatus:String,statusText:String,modelText:String,localProject:Boolean})
defineEmits(['updated'])
</script>
<template>
  <article class="coding-turn" :data-turn="turn.id">
    <div class="coding-user-message">{{ turn.prompt }}</div>
    <div class="coding-agent-label"><span class="agent-mini">✦</span> Coding Agent <span class="turn-model" :title="modelText">{{ modelText }}</span><small>{{ turn.verification && turn.status === 'SUCCEEDED' ? '本轮已结束' : statusText }}</small></div>

    <CodingTeam :session-id="sessionId" :turn-id="turn.id" :events="turn.teamEvents" :session-status="sessionStatus" @updated="$emit('updated', $event)" />
    <ReplyMarkdown v-if="turn.text" :text="turn.text" />
    <p v-else-if="['RUNNING','QUEUED'].includes(String(turn.status).toUpperCase())" class="coding-thinking"><span></span>正在处理任务…</p>
    <ServerCandidate v-if="turn.workPurpose==='PROPOSE_PATCH' && !localProject && ['SUCCEEDED','FAILED','CANCELLED','PARTIAL','INTERRUPTED'].includes(turn.status)" :session-id="sessionId" :turn-id="turn.id"/>
    <CodingVerification v-if="turn.verification" :evidence="turn.verification" />
    <p v-if="turn.attemptMessage" class="coding-service-note" role="status">{{turn.attemptMessage}}</p>
    <details v-if="turn.events.length && (turn.workPurpose || turn.verification)" class="coding-tool-events" :open="turn.status==='RUNNING'">
      <summary>执行记录 · {{turn.events.length}}</summary><CodingToolEvent v-for="event in turn.events" :key="event.seq" :event="event"/>
    </details>
    <div v-else-if="turn.events.length" class="coding-tool-events"><CodingToolEvent v-for="event in turn.events" :key="event.seq" :event="event"/></div>
  </article>
</template>
