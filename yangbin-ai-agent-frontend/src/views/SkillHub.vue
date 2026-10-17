<script setup>
import { ref, computed, onMounted, onBeforeUnmount, watch, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, errorMessage } from '../workspace-api'
import { auth } from '../auth'
import StudioIcon from '../components/StudioIcon.vue'
import '../studio.css'
const route = useRoute(), router = useRouter()
const catalog = ref({ skills: [], partners: [] }), library = ref({ skills: [], partners: [] })
const summoned = ref([])
const capabilities = ref(null), error = ref(''), loading = ref(true), busy = ref(''), query = ref(''), category = ref('全部'), mine = ref(false), detail = ref(null)
const tab = computed(() => ['plugins','partners'].includes(route.query.tab) ? route.query.tab : 'skills')
const owner = () => auth.user?.id || ''
let epoch = 0, controller, alive = true, returnFocus
const entries = computed(() => tab.value === 'partners' ? catalog.value.partners : catalog.value.skills)
const categories = computed(() => ['全部', ...new Set(entries.value.map(s => s.category))])
const filtered = computed(() => entries.value.filter(s => (category.value === '全部' || s.category === category.value)
  && (!mine.value || (tab.value === 'partners' ? summoned.value : library.value.skills).includes(s.id))
  && [s.name,s.description,...s.tags].join(' ').toLowerCase().includes(query.value.toLowerCase().trim())))
const groups = computed(() => [...new Set(filtered.value.map(s => s.category))].map(name => ({ name, items: filtered.value.filter(s => s.category === name) })))
const plugins = computed(() => [
{ id: 'knowledge', name: '个人知识库', icon: 'book', color: 'blue', description: '上传资料、建立索引并检索自己的知识库。', available: !!capabilities.value?.rag, to: '/knowledge', tag: '资料检索' },
{ id: 'mcp', name: 'MCP 工具', icon: 'plug', color: 'violet', description: '查看服务端连接的工具，按当前账号权限调用。', available: !!capabilities.value?.mcpTools?.length, to: '/mcp', tag: '工具连接' },
{ id: 'agent', name: 'Agent 工具执行', icon: 'spark', color: 'teal', description: '在智能对话中调用当前账号可用的工具。', available: !!capabilities.value?.tools?.length, to: '/super-agent', tag: '智能对话' }
].filter(s => [s.name,s.description].join(' ').includes(query.value.trim())))
async function load() {
  const version = ++epoch, account = owner()
  controller?.abort(); controller = new AbortController(); loading.value = true; error.value = ''
  try {
    const [c,l,p] = await Promise.all([api.get('/ai/work/catalog',{ signal: controller.signal }),api.get('/ai/work/library',{ signal: controller.signal }),api.get('/ai/work/partners',{signal:controller.signal})])
    if (!alive || version !== epoch || account !== owner()) return
    catalog.value = c.data; library.value = l.data; summoned.value = p.data
    api.get('/ai/capabilities',{ signal: controller.signal }).then(({data}) => { if (alive && version === epoch && account === owner()) capabilities.value = data }).catch(() => {})
  } catch (e) { if (alive && version === epoch && account === owner() && e.code !== 'ERR_CANCELED') error.value = errorMessage(e) }
  finally { if (alive && version === epoch) loading.value = false }
}
function selected(item) { return (tab.value === 'partners' ? summoned.value : library.value.skills).includes(item.id) }
async function toggle(item) {
  if (busy.value) return
  const account = owner(), version = epoch, kind = tab.value === 'partners' ? 'partner' : 'skill'
  busy.value = item.id; error.value = ''
  try {
    const {data} = await api.put('/ai/work/library/' + kind + '/' + encodeURIComponent(item.id),{ selected: !selected(item) })
    if (alive && version === epoch && account === owner()) library.value = data
  } catch (e) { if (alive && version === epoch && account === owner()) error.value = errorMessage(e) }
  finally { if (alive && version === epoch) busy.value = '' }
}
const isNarrative = item => item?.workspace === 'narrative-ppt'
function destination(item) {
  if (tab.value === 'partners') return '/partners/' + item.id
  return item.kind === 'TRAVEL' ? { path: '/tasks', query: { skill: item.id } } : '/work/' + item.id
}
async function show(item,event) { if(tab.value === 'partners') { router.push(destination(item)); return } returnFocus = event?.currentTarget; detail.value = item; await nextTick(); document.querySelector('.studio-dialog button')?.focus() }
function close() { detail.value = null; returnFocus?.focus() }
function dialogKeys(e) {
  if (e.key === 'Escape') close()
  if (e.key === 'Tab') {
    const els = [...e.currentTarget.querySelectorAll('a[href],button:not(:disabled)')]
    const first = els[0], last = els.at(-1)
    if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last?.focus() }
    else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first?.focus() }
  }
}
watch(tab, () => { category.value = '全部'; mine.value = false; query.value = ''; detail.value = null })
watch(owner, () => { epoch++; controller?.abort(); catalog.value = {skills:[],partners:[]}; library.value = {skills:[],partners:[]}; detail.value = null; capabilities.value = null; busy.value = ''; if (owner()) load() })
onMounted(load)
onBeforeUnmount(() => { alive = false; epoch++; controller?.abort() })
</script>
<template>
  <main class="studio" data-testid="skill-hub">
    <header class="studio-top">
      <nav class="studio-tabs" aria-label="能力类型">
        <RouterLink to="/skills?tab=plugins" :class="{ active: tab === 'plugins' }">插件</RouterLink>
        <RouterLink to="/skills" :class="{ active: tab === 'skills' }">技能</RouterLink>
        <RouterLink to="/skills?tab=partners" :class="{ active: tab === 'partners' }">工作伙伴</RouterLink>
      </nav>
      <div class="studio-tools"><label class="studio-search"><StudioIcon name="search" :size="18" /><input v-model="query" :aria-label="tab === 'partners' ? '搜索伙伴' : '搜索技能或插件'" :placeholder="tab === 'partners' ? '搜索伙伴' : tab === 'plugins' ? '搜索插件' : '搜索技能'"></label>
        <button v-if="tab !== 'plugins'" class="studio-btn" :class="{ selected: mine }" :aria-pressed="mine" @click="mine = !mine"><StudioIcon :name="tab === 'partners' ? 'person' : 'grid'" :size="16" />{{ tab === 'partners' ? '我的伙伴' : '我的技能' }}</button>
      </div>
    </header>
    <div class="studio-body">
      <div v-if="tab !== 'plugins'" class="category-strip" aria-label="领域分类"><button v-for="c in categories" :key="c" :class="{ active: category === c }" :aria-pressed="category === c" @click="category = c">{{ c }}</button></div>
      <section v-if="!mine && !query && category === '全部'" class="studio-banner">
        <div><div class="banner-eyebrow">A LITTLE HELP. A LOT MORE POSSIBILITY.</div>
          <h1>{{ tab === 'partners' ? '找到与你一起做事的伙伴' : tab === 'plugins' ? '连接资料，让工作多一种可能' : '为每一项工作，找到合适的能力' }}</h1>
          <p>{{ tab === 'partners' ? 'NarrativePPT 直接制作演示文件；其他伙伴基于你提供的材料生成文本初稿。' : tab === 'plugins' ? '这里展示服务端实际接入的能力，按当前账号的权限使用。' : '从一份会议纪要到一场营销活动，让不同领域的方法触手可及。' }}</p>
        </div><div class="banner-mark"><StudioIcon :name="tab === 'partners' ? 'person' : 'layers'" :size="36" /></div>
      </section>
      <div v-if="error" class="studio-error" role="alert">{{ error }} <button class="text-action" @click="load">重新加载</button></div>
      <p v-if="loading" class="studio-note" role="status">正在读取技能与伙伴…</p>
      <template v-if="tab === 'plugins'">
        <div class="section-heading"><h2>已接入的能力</h2><span>以服务实际状态为准</span></div>
        <div class="capability-grid"><article v-for="plugin in plugins" :key="plugin.id" class="capability-card">
          <div class="capability-icon" :class="plugin.color"><StudioIcon :name="plugin.icon" :size="26" /></div>
          <div class="capability-copy"><h3 class="capability-name">{{ plugin.name }}</h3><p class="capability-description">{{ plugin.description }}</p><div class="capability-tags"><span>{{ plugin.tag }}</span><span>{{ capabilities === null ? '状态未获取' : plugin.available ? '服务已启用' : '当前未启用' }}</span></div><div class="card-actions"><RouterLink class="studio-btn" :to="plugin.to">查看能力 <StudioIcon name="arrow" :size="13" /></RouterLink></div></div>
        </article></div>
        <p class="studio-note" style="margin-top:30px">文本技能目前使用你粘贴的材料；这些外部能力在各自入口使用，不会自动加入伙伴任务。</p>
      </template>
      <template v-else>
        <section v-for="group in groups" :key="group.name" :aria-label="group.name">
          <div class="section-heading"><h2>{{ group.name }}</h2><span>{{ group.items.length }} {{ tab === 'partners' ? '位伙伴' : '项技能' }}</span></div>
          <div class="capability-grid"><article v-for="item in group.items" :key="item.id" class="capability-card" :data-id="item.id">
            <div class="capability-icon" :class="[item.color,{ 'partner-avatar': tab === 'partners' }]"><span v-if="tab === 'partners'">{{ item.initial }}</span><StudioIcon v-else :name="item.icon" :size="25" /></div>
            <div class="capability-copy"><button class="capability-name" @click="show(item,$event)">{{ item.name }}</button><p class="capability-description">{{ item.description }}</p><div class="capability-tags"><span v-if="tab === 'partners' && !isNarrative(item)">文本初稿</span><span v-for="tag in item.tags" :key="tag">{{ tag }}</span></div>
              <div class="card-actions"><RouterLink class="studio-btn" :to="destination(item)">{{ tab === 'partners' ? (summoned.includes(item.id) ? '进入伙伴' : '召唤') : '使用技能' }}<StudioIcon name="arrow" :size="12" /></RouterLink><button v-if="tab !== 'partners'" class="text-action" :disabled="!!busy" :aria-label="(selected(item) ? '取消收藏' : '收藏') + item.name" @click="toggle(item)">{{ busy === item.id ? '保存中' : selected(item) ? '✓ 已收藏' : '＋ 收藏' }}</button></div>
            </div>
          </article></div>
        </section>
        <div v-if="!loading && !filtered.length && !error" class="studio-empty"><StudioIcon name="search" :size="32" /><h2>{{ mine ? '这里还没有添加的内容' : '没有找到匹配结果' }}</h2><p>{{ mine ? '浏览广场，收藏常用技能或伙伴。' : '试试其他关键词或领域。' }}</p><button class="studio-btn" @click="mine = false; query = ''; category = '全部'">查看全部</button></div>
      </template>
    </div>
    <div v-if="detail" class="drawer-shade" @click.self="close" @keydown="dialogKeys">
      <section class="studio-dialog" role="dialog" aria-modal="true" aria-labelledby="capability-title">
        <div class="dialog-heading"><h2 id="capability-title">{{ detail.name }}</h2><button aria-label="关闭详情" @click="close"><StudioIcon name="close" /></button></div>
        <p>{{ detail.description }}</p>
        <h3>可以获得什么</h3><p>{{ isNarrative(detail) ? '带演讲者备注的可编辑 PPTX，支持逐页在线编辑、主题和配图管理。' : detail.output || catalog.skills.find(s => s.id === detail.skillId)?.output }}</p>
        <h3>工作方式</h3><p>{{ isNarrative(detail) ? '进入 NarrativePPT 工作台，上传文档或粘贴材料，选择听众与目标，审阅大纲后生成页面，编辑完成后导出。' : detail.instructions || catalog.skills.find(s => s.id === detail.skillId)?.instructions || '跳转到出行工作台，选择技能、确认计划后生成带来源依据的行程。' }}</p>
        <p class="studio-note" style="margin-top:18px">{{ isNarrative(detail) ? '复用当前账号，无需再次登录。AI生成与修改使用工作台中的模型配置。' : detail.kind === 'TRAVEL' ? '天气、地点和路线的可用性取决于服务配置。' : (detail.id === 'presentation-outline' || detail.skillId === 'presentation-outline') ? '基于粘贴材料生成逐页内容，支持下载可编辑PPTX与演讲者备注。当前为文字演示，不自动制作图表或配图。' : '基于粘贴材料生成文本初稿。图片、视频、PPTX制作及外部资料自动检索尚未接入此任务。' }}</p>
        <div class="card-actions"><RouterLink class="studio-btn primary" :to="destination(detail)">开始任务 <StudioIcon name="arrow" :size="15" /></RouterLink></div>
      </section>
    </div>
  </main>
</template>
