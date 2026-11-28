<script setup>
import { computed, ref, watch } from 'vue'
import { api, errorMessage } from '../workspace-api'
import { workspaceOwner } from '../workspace-state'
import StudioIcon from '../components/StudioIcon.vue'
import '../blog.css'

const posts = ref([]), loading = ref(true), error = ref('')
const filter = ref('all'), query = ref('')
const filters = [['all', '全部'], ['PUBLISHED', '已发布'], ['DRAFT', '草稿']]
const published = computed(() => posts.value.filter(post => post.publishedAt))
const filtered = computed(() => {
  const needle = query.value.trim().toLocaleLowerCase()
  return posts.value.filter(post => {
    if (filter.value === 'PUBLISHED' && !post.publishedAt) return false
    if (filter.value === 'DRAFT' && post.status !== 'DRAFT') return false
    return [post.title || '', ...(Array.isArray(post.tags) ? post.tags : [])].some(value => String(value ?? '').toLocaleLowerCase().includes(needle))
  })
})
const date = value => new Date(value).toLocaleDateString('zh-CN', { year: 'numeric', month: 'long', day: 'numeric' })

async function load() {
  const owner = workspaceOwner()
  loading.value = true
  error.value = ''
  posts.value = []
  try {
    const { data } = await api.get('/ai/blog/posts')
    if (workspaceOwner() === owner) posts.value = data
  } catch (e) {
    if (workspaceOwner() === owner) error.value = errorMessage(e)
  } finally {
    if (workspaceOwner() === owner) loading.value = false
  }
}
watch(workspaceOwner, load, { immediate: true })

</script>

<template>
  <main class="blog blog-index">
    <header class="blog-masthead">
      <div><h1>我的博客</h1><p v-if="!loading && !error" class="blog-meta">{{ posts.length }} 篇文章 · {{ published.length }} 篇已发布</p></div>
      <div class="blog-actions">
        <RouterLink class="blog-primary" to="/blog/new" data-testid="blog-new"><StudioIcon name="pen" :size="17"/>写文章</RouterLink>
      </div>
    </header>
    <div class="blog-index-tools">
      <div class="blog-tabs" role="group" aria-label="文章状态">
        <button v-for="[value,label] in filters" :key="value" :aria-pressed="filter === value" @click="filter = value">{{ label }}</button>
      </div>
      <label class="blog-search"><StudioIcon name="search" :size="17"/><input v-model="query" type="search" placeholder="搜索标题或标签" aria-label="搜索标题或标签" data-testid="blog-search"></label>
    </div>
    <p v-if="error" role="alert" class="blog-error">{{ error }} <button @click="load">刷新列表</button></p>
    <p v-if="loading" class="blog-empty">正在读取文章…</p>
    <section v-else-if="filtered.length" aria-label="文章归档">
      <article v-for="post in filtered" :key="post.id" class="blog-row">
        <time class="blog-meta" :datetime="post.updatedAt">{{ date(post.updatedAt) }}</time>
        <div><h2><RouterLink :to="'/blog/' + post.id">{{ post.title || '无标题草稿' }}</RouterLink></h2>
          <div v-if="post.tags?.length" class="blog-tags"><span v-for="tag in post.tags" :key="tag">{{ tag }}</span></div>
        </div>
        <span class="blog-status" :class="{published:!!post.publishedAt}">{{ post.status === 'PUBLISHED' ? '已发布' : post.publishedAt ? '已发布 · 有新草稿' : '草稿' }}</span>
      </article>
    </section>
    <p v-else-if="!error" class="blog-empty">{{ posts.length ? '没有符合条件的文章。' : '还没有文章。点「写文章」开始第一篇。' }}</p>
  </main>
</template>
