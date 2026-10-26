// User-supplied editor preferences stay on this browser, never in project/API payloads.
export function normalizeEditorPath(value) {
  let path = String(value || '').trim()
  if (path.length > 4096 || /[\x00-\x1f\x7f]/.test(path)) throw new Error('目录路径包含不支持的字符。')
  if ((path.startsWith('"') && path.endsWith('"')) || (path.startsWith("'") && path.endsWith("'"))) path = path.slice(1, -1)
  path = path.replaceAll('\\', '/')
  const windows = /^[a-z]:\//i.test(path)
  if ((!windows && !path.startsWith('/')) || path.startsWith('//')) throw new Error('请输入本机绝对目录路径，例如 C:\\Projects\\my-app；暂不支持网络共享路径。')
  if (/[<>"|?*]/.test(path) || path.slice(windows ? 2 : 0).includes(':')) throw new Error('请输入目录路径，不要填写网址或文件行号。')
  if (path.split('/').some(part => part === '.' || part === '..')) throw new Error('请填写完整目录路径，不使用 . 或 ..。')
  path = path.replace(/\/+$/, '')
  if (!path || /^[a-z]:$/i.test(path)) throw new Error('请选择项目文件夹，不要使用磁盘根目录。')
  return path
}
export function editorUrl(value) {
  const path = normalizeEditorPath(value)
  const encoded = path.split('/').map((part, index) => index === 0 && /^[a-z]:$/i.test(part) ? part : encodeURIComponent(part)).join('/')
  return `vscode://file/${encoded.startsWith('/') ? encoded.slice(1) : encoded}/`
}
export const editorPreferenceKey = (owner, project) => `yangbin-editor-v1:${encodeURIComponent(owner)}:${encodeURIComponent(project)}`
export function readEditorPath(owner, project) {
  if (!owner || !project) return ''
  try { const value = localStorage.getItem(editorPreferenceKey(owner, project)); return value ? normalizeEditorPath(value) : '' } catch { return '' }
}
export function saveEditorPath(owner, project, value) {
  if (!owner || !project) throw new Error('请先选择项目。')
  const key = editorPreferenceKey(owner, project)
  if (!value) localStorage.removeItem(key)
  else localStorage.setItem(key, normalizeEditorPath(value))
}
