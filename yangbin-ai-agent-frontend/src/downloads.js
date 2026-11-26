export function downloadBlob(blob, filename) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  try {
    link.click()
  } finally {
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  }
}

export function downloadMarkdown(text, filename) {
  downloadBlob(new Blob([text], { type: 'text/markdown;charset=utf-8' }), filename)
}
