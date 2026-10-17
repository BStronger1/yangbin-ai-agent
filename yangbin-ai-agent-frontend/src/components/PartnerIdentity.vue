<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
const props=defineProps({partner:Object,partners:Array,details:Boolean})
const router=useRouter(), menu=ref(null)
function switchTo(id){if(menu.value)menu.value.open=false;router.push('/partners/'+id)}
</script>
<template>
<section class="partner-identity">
  <div class="capability-icon partner-portrait" :class="partner.color">{{partner.initial}}</div>
  <div class="partner-summary">
    <h1 v-if="details">{{partner.name}}</h1>
    <details v-else ref="menu" class="partner-switch" @keydown.esc="menu.open=false">
      <summary aria-label="切换已召唤伙伴">{{partner.name}} <span>⌄</span></summary>
      <div class="partner-menu"><div class="partner-menu-title">我的伙伴 <RouterLink to="/skills?tab=partners" aria-label="召唤更多伙伴">＋</RouterLink></div>
        <button v-for="item in partners" :key="item.id" :aria-current="item.id===partner.id?'true':undefined" @click="switchTo(item.id)"><span class="partner-mini" :class="item.color">{{item.initial}}</span><span>{{item.name}}</span><span v-if="item.id===partner.id">✓</span></button>
      </div>
    </details>
    <p>{{partner.description}}</p>
  </div>
  <RouterLink v-if="!details" class="partner-detail-link" :to="'/partners/'+partner.id+'/details'">伙伴详情 ›</RouterLink>
  <RouterLink v-else class="studio-btn" :to="'/partners/'+partner.id">＋ 新任务</RouterLink>
</section>
</template>
