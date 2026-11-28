<script setup>
import {computed, onMounted, onBeforeUnmount, ref, watch} from 'vue'
import {marked} from 'marked'
import createDOMPurify from 'dompurify'
const DOMPurify = createDOMPurify(window)
DOMPurify.addHook('afterSanitizeAttributes', node => {
  if (node.tagName === 'A' && /^https?:\/\//i.test(node.getAttribute('href') || '')) {
    node.setAttribute('target', '_blank')
    node.setAttribute('rel', 'noopener noreferrer')
  }
})
const props=defineProps({text:{type:String,default:''}})
const html=computed(()=>DOMPurify.sanitize(marked.parse(props.text,{breaks:true,gfm:true}),{FORBID_TAGS:['img','style','iframe','svg','form','input'],FORBID_ATTR:['style']}))
const root = ref(null)
let copyTargets = new WeakMap()
const timers = new Set()
function clearTimers() { timers.forEach(clearTimeout); timers.clear() }
function decorateCode() {
  clearTimers()
  copyTargets = new WeakMap()
  root.value?.querySelectorAll('pre').forEach(pre => {
    const code = pre.querySelector('code')
    if (!code) return
    const frame = document.createElement('div')
    frame.className = 'reply-code-frame'
    const bar = document.createElement('div')
    bar.className = 'reply-code-toolbar'
    const language = document.createElement('span')
    const detected = [...code.classList].find(value => value.startsWith('language-'))?.slice(9)
    language.textContent = ({ js: 'javascript', ts: 'typescript', py: 'python', sh: 'bash' })[detected] || detected || 'text'
    const copy = document.createElement('button')
    copy.type = 'button'
    copy.className = 'reply-code-copy'
    copy.textContent = '复制'
    copy.setAttribute('aria-label', '复制代码')
    copy.setAttribute('aria-live', 'polite')
    copyTargets.set(copy, code.textContent || '')
    bar.append(language, copy)
    pre.before(frame)
    frame.append(bar, pre)
  })
}
function fallbackCopy(text) {
  const active = document.activeElement, selection = window.getSelection()
  const ranges = selection ? Array.from({ length: selection.rangeCount }, (_, i) => selection.getRangeAt(i).cloneRange()) : []
  const field = document.createElement('textarea')
  field.value = text
  field.setAttribute('readonly', '')
  field.style.cssText = 'position:fixed;left:0;top:0;width:1px;height:1px;opacity:0;pointer-events:none'
  document.body.append(field)
  try {
    field.select()
    if (!document.execCommand('copy')) throw new Error('Copy unavailable')
  } finally {
    field.remove()
    active?.focus?.({ preventScroll: true })
    if (selection) { selection.removeAllRanges(); ranges.forEach(range => selection.addRange(range)) }
  }
}
async function copyCode(event) {
  const button = event.target.closest?.('button')
  if (!button || !copyTargets.has(button) || button.disabled) return
  const text = copyTargets.get(button)
  button.disabled = true
  try {
    try {
      if (!navigator.clipboard?.writeText) throw new Error('Clipboard unavailable')
      await navigator.clipboard.writeText(text)
    } catch { fallbackCopy(text) }
    button.textContent = '已复制 ✓'
  } catch { button.textContent = '复制失败，请重试' }
  finally {
    button.disabled = false
    if (button.isConnected) {
      const timer = setTimeout(() => { button.textContent = '复制'; timers.delete(timer) }, 2000)
      timers.add(timer)
    }
  }
}
onMounted(decorateCode)
watch(html, decorateCode, { flush: 'post' })
onBeforeUnmount(clearTimers)
</script>
<template><div ref="root" class="reply-markdown" @click="copyCode" v-html="html"></div></template>
<style>
.reply-markdown{min-width:0;max-width:100%;font-size:15px;line-height:1.95;color:#252c37;overflow-wrap:anywhere}.reply-markdown p{margin:12px 0}.reply-markdown h1,.reply-markdown h2,.reply-markdown h3{font-weight:650;line-height:1.5;margin:24px 0 12px}.reply-markdown h1{font-size:24px}.reply-markdown h2{font-size:21px}.reply-markdown h3{font-size:18px}.reply-markdown ul,.reply-markdown ol{padding-left:25px;margin:12px 0}.reply-markdown li{margin:6px 0}.reply-markdown hr{border:0;border-top:1px solid #eceef2;margin:24px 0}.reply-markdown pre{max-width:100%;min-width:0;box-sizing:border-box;white-space:pre;overflow-wrap:normal;word-break:normal;overflow-x:auto;background:#f6f7f9;border:1px solid #eceef2;border-radius:12px;padding:18px;line-height:1.65;margin:18px 0}.reply-markdown code{font-size:13px;background:#f4f5f7;padding:2px 4px;border-radius:4px}.reply-markdown pre code{padding:0;background:none;white-space:pre;overflow-wrap:normal;word-break:normal}.reply-markdown table{display:block;max-width:100%;overflow-x:auto;border-collapse:collapse;margin:18px 0}.reply-markdown td,.reply-markdown th{overflow-wrap:normal;word-break:keep-all;white-space:nowrap;border:1px solid #e3e7ee;padding:10px 16px;min-width:90px}.reply-markdown th{background:#f6f8fb;text-align:left}.reply-markdown blockquote{border-left:3px solid #a9b9e7;padding-left:18px;color:#798391}.reply-markdown a{color:#3768df;text-decoration:underline}
/* The toolbar is created after sanitization; copy targets never come from HTML attributes. */
.reply-markdown{color:var(--yb-ink-secondary);line-height:1.9}.reply-markdown p{margin:14px 0}
.reply-markdown>:first-child{margin-top:0}.reply-markdown>:last-child{margin-bottom:0}
.reply-markdown h1,.reply-markdown h2,.reply-markdown h3{color:var(--yb-ink);letter-spacing:-.02em}
.reply-markdown hr{border-color:var(--yb-border)}
.reply-markdown code{font-family:var(--yb-font-mono);background:var(--yb-bg-subtle)}
.reply-markdown pre{background:var(--yb-bg-subtle);border-color:var(--yb-border);line-height:1.7}
.reply-code-frame{min-width:0;max-width:100%;margin:20px 0;border:1px solid var(--yb-border);border-radius:12px;overflow:hidden;background:var(--yb-bg-subtle)}
.reply-code-toolbar{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:8px 14px;background:var(--yb-bg-surface);border-bottom:1px solid var(--yb-border);color:var(--yb-ink-muted);font:11px/1.6 var(--yb-font-mono)}
.reply-code-toolbar>span{min-width:0;overflow-wrap:anywhere}
.reply-code-copy{flex-shrink:0;border:1px solid transparent;border-radius:6px;padding:4px 8px;background:transparent;color:var(--yb-ink-secondary);font:12px/1.4 var(--yb-font-sans);cursor:pointer;transition:background var(--yb-transition),color var(--yb-transition)}
.reply-code-copy:hover{background:var(--yb-accent-soft);color:var(--yb-accent)}.reply-code-copy:focus-visible{outline:2px solid var(--yb-accent);outline-offset:2px}
.reply-markdown .reply-code-frame pre{border:0;border-radius:0;margin:0;padding:16px 18px}
.reply-markdown table{font-variant-numeric:tabular-nums}.reply-markdown td,.reply-markdown th{border-color:var(--yb-border)}
.reply-markdown th{background:var(--yb-bg-subtle);color:var(--yb-ink);font-weight:600}
.reply-markdown tbody tr:nth-child(even){background:var(--yb-bg-canvas)}.reply-markdown tbody tr:hover{background:var(--yb-bg-hover)}
.reply-markdown blockquote{margin:20px 0;border-left:3px solid var(--yb-border-strong);border-radius:0 8px 8px 0;padding:2px 18px;background:var(--yb-bg-canvas);color:var(--yb-ink-muted)}
.reply-markdown a{color:var(--yb-accent);text-underline-offset:3px}
@media(max-width:480px){.reply-markdown{font-size:14px}.reply-markdown .reply-code-frame pre{padding:14px}.reply-code-toolbar{padding:7px 12px}}
</style>
