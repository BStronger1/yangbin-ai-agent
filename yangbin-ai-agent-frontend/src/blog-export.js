import { downloadMarkdown } from './downloads'

export function downloadHexoPost(post) {
  const title = post.title?.trim() || '未命名文章'
  const metadata = [
    '---',
    'title: ' + JSON.stringify(title),
    'tags: ' + JSON.stringify(post.tags || []),
    'published: ' + (post.status === 'PUBLISHED'),
    'disableNunjucks: true'
  ]
  const date = post.publishedAt || post.createdAt
  if (date) metadata.push('date: ' + JSON.stringify(date))
  if (post.updatedAt) metadata.push('updated: ' + JSON.stringify(post.updatedAt))
  let filename = title.replace(/[<>:"/\\|?*\x00-\x1f]/g, '_').slice(0, 100).replace(/[. ]+$/g, '')
  if (!filename || /^\.+$/.test(filename)) filename = '未命名文章'
  if (/^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\.|$)/i.test(filename)) filename = '_' + filename
  downloadMarkdown([...metadata, '---', '', post.markdown || ''].join('\n'), filename + '.md')
}
