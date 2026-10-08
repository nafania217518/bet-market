/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
      '/ws': { target: 'ws://localhost:8080', ws: true },
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['src/setup.ts', 'src/test-utils.tsx'],
    coverage: {
      include: ['src/**'],
      exclude: ['src/main.tsx', 'src/**/*.d.ts', 'src/**/*.test.*', 'src/setup.ts', 'src/test-utils.tsx'],
      thresholds: { statements: 90, lines: 90 },
    },
  },
})
