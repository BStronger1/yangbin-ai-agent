import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath } from 'node:url'
import { readdir, readFile, writeFile } from 'node:fs/promises'
import { gzipSync } from 'node:zlib'
import path from 'node:path'

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [vue(), {
    name: 'precompress-static-assets',
    apply: 'build',
    async closeBundle() {
      const directory = fileURLToPath(new URL('./dist/assets/', import.meta.url))
      await Promise.all((await readdir(directory)).filter(name => /\.(js|css)$/.test(name)).map(async name => {
        const bytes = await readFile(path.join(directory, name))
        // aiohttp FileResponse negotiates the sidecar using Accept-Encoding.
        await writeFile(path.join(directory, name + '.gz'), gzipSync(bytes, { level: 9 }))
      }))
    }
  }],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    port: 3000,
    proxy: { "/api": { target: "http://127.0.0.1:8123", changeOrigin: true } }
  }
})
