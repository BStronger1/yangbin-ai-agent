import { onBeforeUnmount, onMounted, unref, watch } from 'vue'
import { registerOverlay } from './useOverlay'

// A native top-layer dialog also works above a teleported or native parent dialog.
export function confirmDiscard() {
  let dismiss
  const result = new Promise(resolve => {
    const dialog = document.createElement('dialog')
    dialog.className = 'unsaved-confirm'
    dialog.setAttribute('aria-label', '未保存的修改')
    dialog.setAttribute('data-testid', 'unsaved-confirm')
    dialog.innerHTML = '<h2>保留未保存的修改？</h2><p>离开会丢弃当前未保存的修改。</p><footer><button type="button" data-overlay-initial>继续编辑</button><button type="button" data-testid="discard-changes">丢弃修改</button></footer>'
    Object.assign(dialog.style, { margin:'auto', padding:'24px', border:'1px solid #cbd5e1', borderRadius:'14px', maxWidth:'min(420px, calc(100vw - 32px))', color:'#334155', font:'inherit', boxShadow:'0 20px 80px #14253d33' })
    document.body.append(dialog)
    let done = false, release
    const finish = accepted => { if (done) return; done = true; release?.(); dialog.remove(); resolve(accepted) }
    dismiss = () => finish(false)
    Object.assign(dialog.querySelector('footer').style, {display:'flex', justifyContent:'flex-end', gap:'12px', marginTop:'20px'})
    const buttons = dialog.querySelectorAll('button')
    for (const button of buttons) Object.assign(button.style, {border:'1px solid #cbd5e1', borderRadius:'8px', padding:'9px 12px', cursor:'pointer', background:'#fff', color:'#334155'})
    buttons[0].onclick = () => finish(false)
    buttons[1].onclick = () => finish(true)
    release = registerOverlay(dialog, () => finish(false))
  })
  result.cancel = () => dismiss?.()
  return result
}
export function useUnsavedChanges(dirty, busy = false, scope) {
  let pending = null, dismiss
  if (scope) watch(scope, () => dismiss?.(), {flush:'sync'})
  const allowDiscard = () => {
    if (unref(busy)) return Promise.resolve(false)
    if (!unref(dirty)) return Promise.resolve(true)
    if (!pending) { const confirmation = confirmDiscard(); dismiss = confirmation.cancel; pending = confirmation.finally(() => { pending = null; dismiss = null }) }
    return pending
  }
  const unload = event => { if (unref(dirty) || unref(busy)) { event.preventDefault(); event.returnValue = '' } }
  onMounted(() => window.addEventListener('beforeunload', unload))
  onBeforeUnmount(() => { dismiss?.(); window.removeEventListener('beforeunload', unload) })
  return { allowDiscard }
}
