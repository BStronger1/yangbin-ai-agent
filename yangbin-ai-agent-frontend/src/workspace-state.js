import { auth } from './auth'

// Browser preferences are conveniences, never authorization or task truth.
export const workspaceOwner = () => auth.user?.id || auth.user?.username || ''
const key = (space, part, owner = workspaceOwner()) => `workspace-v1:${owner}:${space}:${part}`
export function readWorkspace(space, part, fallback = null, owner = workspaceOwner()) {
  if (!owner) return fallback
  try { const value = sessionStorage.getItem(key(space, part, owner)); return value === null ? fallback : JSON.parse(value) } catch { return fallback }
}
export function writeWorkspace(space, part, value, owner = workspaceOwner()) {
  if (!owner) return
  try { if (value === null) sessionStorage.removeItem(key(space, part, owner)); else sessionStorage.setItem(key(space, part, owner), JSON.stringify(value)) } catch { /* Restricted storage must not break the workspace. */ }
}
export function workspaceLink(space) {
  const current = readWorkspace(space, 'last')
  if (space === 'coding') return { path: '/coding', query: current?.session ? { session: current.session } : {} }
  return { path: '/assistant', query: { mode: space === 'assistant-work' ? 'work' : 'chat', ...(current?.conversation ? { conversation: current.conversation } : {}), ...(current?.partner && current.partner !== 'office' ? { partner: current.partner } : {}) } }
}
