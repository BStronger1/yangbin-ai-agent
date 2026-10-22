import { nextTick, onBeforeUnmount, watch, unref } from 'vue'

// One stack across component trees and Teleports. Closing UI never decides a task.
const layers = []
let composing = false, serial = 0
const focusable = root => [...root.querySelectorAll('button,input,select,textarea,a[href],summary,[tabindex]')]
  .filter(node => !node.matches(':disabled') && node.tabIndex >= 0 && !node.closest('[inert]') && node.getClientRects().length)
const visible = node => node?.isConnected && node.getClientRects().length && !node.closest('[inert]')
const top = () => layers.at(-1)
export const isComposing = event => composing || event?.isComposing || event?.keyCode === 229
const compositionStart = () => { composing = true }
const compositionEnd = () => { composing = false }
function focusFirst(layer) {
  const root = layer?.element
  if (!root?.isConnected) return
  const target = root.querySelector('[data-overlay-initial]') || focusable(root)[0] || root
  if (!target.hasAttribute('tabindex') && target === root) target.tabIndex = -1
  target.focus({ preventScroll: true })
}
function keys(event) {
  const layer = top()
  if (!layer || isComposing(event)) return
  if (event.key === 'Escape') {
    event.preventDefault(); event.stopImmediatePropagation()
    if (!event.repeat) layer.close('escape')
  } else if (event.key === 'Tab') {
    const nodes = focusable(layer.element), current = document.activeElement
    if (!nodes.length) { event.preventDefault(); focusFirst(layer); return }
    if (!layer.element.contains(current) || (event.shiftKey && current === nodes[0]) || (!event.shiftKey && current === nodes.at(-1))) {
      event.preventDefault(); (event.shiftKey ? nodes.at(-1) : nodes[0]).focus()
    }
  }
}
function keepFocus(event) {
  const layer = top()
  if (layer?.modal && !layer.element.contains(event.target)) focusFirst(layer)
}
function listen(active) {
  const method = active ? 'addEventListener' : 'removeEventListener'
  document[method]('keydown', keys, true)
  document[method]('focusin', keepFocus, true)
  document[method]('compositionstart', compositionStart, true)
  document[method]('compositionend', compositionEnd, true)
  if (!active) composing = false
}
export function registerOverlay(element, close, { opener = document.activeElement, modal = true } = {}) {
  const layer = { element, close, opener, modal }
  const visual = element.closest('.model-modal-shade,.assistant-dialog-backdrop') || element
  const previousZ = visual.style.zIndex
  visual.style.zIndex = String(1000 + ++serial)
  const cancel = event => { event.preventDefault(); if (top() === layer && !isComposing(event)) close('escape') }
  if (!layers.length) listen(true)
  layers.push(layer)
  element.addEventListener('cancel', cancel)
  if (element.tagName === 'DIALOG' && !element.open) element.showModal()
  focusFirst(layer)
  let removed = false
  return () => {
    if (removed) return
    removed = true
    const wasTop = top() === layer
    layers.splice(layers.indexOf(layer), 1)
    element.removeEventListener('cancel', cancel)
    visual.style.zIndex = previousZ
    if (element.tagName === 'DIALOG' && element.open) element.close()
    if (!layers.length) listen(false)
    if (wasTop) nextTick(() => {
      const active = top()
      if (visible(opener) && (!active?.modal || active.element.contains(opener))) opener.focus({ preventScroll: true })
      else if (active) focusFirst(active)
    })
  }
}
export function useOverlay(open, element, close, options = {}) {
  let release, sequence = 0
  watch(() => Boolean(typeof open === 'function' ? open() : unref(open)), async value => {
    const token = ++sequence, opener = document.activeElement
    if (!value) { release?.(); release = null; return }
    await nextTick()
    if (token !== sequence || !element.value) return
    release = registerOverlay(element.value, close, { ...options, opener })
  }, { immediate: true, flush: 'post' })
  onBeforeUnmount(() => { sequence++; release?.(); release = null })
}
