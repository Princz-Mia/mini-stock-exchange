import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

export default defineConfig({
  plugins: [react()],
  server: {
    // Same-origin in development too: API and SSE go through the Compose reverse proxy.
    proxy: { '/api': 'http://localhost:8080' },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: './src/test-setup.ts',
    // Browser tests under e2e/ run with Playwright against a running stack.
    include: ['src/**/*.test.{ts,tsx}'],
    // CSS is skipped in tests unless named here; the theme tests read the stylesheet itself.
    css: { include: [/styles\.css/] },
  },
});
