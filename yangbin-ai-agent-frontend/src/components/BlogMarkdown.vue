<script setup>
import { computed } from 'vue'
import { marked } from 'marked'
import createDOMPurify from 'dompurify'

const props = defineProps({ text: { type: String, default: '' } })
// Keep these hooks local to articles; chat has its own rendering policy.
const purifier = createDOMPurify(window)
purifier.addHook('uponSanitizeAttribute', (node, attr) => {
  if (node.tagName === 'IMG' && attr.attrName === 'src') {
    try {
      const url = new URL(attr.attrValue)
      attr.keepAttr = url.protocol === 'https:' && !url.username && !url.password
    } catch { attr.keepAttr = false }
  }
})
purifier.addHook('afterSanitizeAttributes', node => {
  if (node.tagName === 'IMG') {
    node.setAttribute('loading', 'lazy')
    node.setAttribute('referrerpolicy', 'no-referrer')
  }
})
const html = computed(() => purifier.sanitize(marked.parse(props.text, { gfm: true }), {
  ALLOWED_TAGS: ['p','br','hr','h1','h2','h3','h4','h5','h6','blockquote','pre','code','em','strong','del',
    'ul','ol','li','table','thead','tbody','tr','th','td','a','img','sup','sub','details','summary'],
  ALLOWED_ATTR: ['href','src','alt','title','start'],
  ALLOW_DATA_ATTR: false,
  ALLOW_ARIA_ATTR: false
}))
</script>
<template><div class="blog-markdown" v-html="html"></div></template>
