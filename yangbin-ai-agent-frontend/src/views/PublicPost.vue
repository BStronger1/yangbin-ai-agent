<script setup>
import { ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import BlogMarkdown from '../components/BlogMarkdown.vue'
import '../blog.css'
const route = useRoute(), post = ref(null), error = ref('')
watch(() => route.params.id, async (id, previous, onCleanup) => {
  const controller = new AbortController()
  onCleanup(() => controller.abort())
  post.value = null
  error.value = ''
  try {
    const response = await fetch('/api/open/blog/' + encodeURIComponent(id), {
      credentials: 'omit', cache: 'no-store', signal: controller.signal
    })
    if (!response.ok) {
      error.value = response.status === 404 ? '这篇文章尚未公开或已被删除。' : '文章暂时无法读取，请刷新重试。'
      return
    }
    const data = await response.json()
    if (!controller.signal.aborted) {
      post.value = data
      document.title = data.title + ' · yangbin AI'
    }
  } catch {
    if (!controller.signal.aborted) error.value = '文章暂时无法读取，请刷新重试。'
  }
}, { immediate: true })
</script>
<template>
  <main class="blog blog-public">
    <article v-if="post" class="blog-paper"><p class="blog-meta">发布于 {{ new Date(post.publishedAt).toLocaleDateString('zh-CN') }}</p>
      <h1>{{ post.title }}</h1>
      <div class="blog-tags"><span v-for="tag in post.tags" :key="tag">{{ tag }}</span></div>
      <BlogMarkdown :text="post.markdown"/>
    </article>
    <p v-else-if="error" role="alert" class="blog-empty">{{ error }}</p>
    <p v-else class="blog-empty">正在读取文章…</p>
  </main>
</template>
