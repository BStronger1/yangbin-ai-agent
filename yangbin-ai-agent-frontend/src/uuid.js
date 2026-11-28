// getRandomValues remains available on HTTP origins where randomUUID is absent.
export function uuid() {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID()
  const bytes = new Uint8Array(16)
  globalThis.crypto.getRandomValues(bytes)
  bytes[6] = (bytes[6] & 15) | 64
  bytes[8] = (bytes[8] & 63) | 128
  return [...bytes].map((value, index) => ([4, 6, 8, 10].includes(index) ? '-' : '') + value.toString(16).padStart(2, '0')).join('')
}
