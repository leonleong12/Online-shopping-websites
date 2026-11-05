import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { cpSync, mkdirSync, readdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { resolve } from 'node:path'
const staticDir = fileURLToPath(new URL('../src/main/resources/static/', import.meta.url))
const runtimeDir = fileURLToPath(new URL('../target/classes/static/', import.meta.url))
export default defineConfig({
  plugins: [vue(), {
    name: 'sync-local-backend-static',
    apply: 'build',
    closeBundle() {
      // IntelliJ runs target/classes, while the distributable uses resources/static.
      // Copy assets before index.html so a running local server never sees a broken entry.
      mkdirSync(runtimeDir, { recursive: true })
      const files = readdirSync(staticDir).sort((a, b) => (a === 'index.html') - (b === 'index.html'))
      for (const file of files) cpSync(resolve(staticDir, file), resolve(runtimeDir, file), { recursive: true })
    }
  }],
  build: {
    outDir: '../src/main/resources/static',
    emptyOutDir: true
  },
  server: {
    proxy: {
      '/api': 'http://localhost:8081',
      '/uploads': 'http://localhost:8081'
    }
  }
})
