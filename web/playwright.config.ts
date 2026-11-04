import { defineConfig, devices } from '@playwright/test';

// Browser tests run against a running stack, never a dev server: the compatibility gate seeds a market, then runs
// them through the reverse proxy. E2E_BASE_URL says where that stack is.
const baseURL = process.env.E2E_BASE_URL;
if (baseURL === undefined) {
  throw new Error('Set E2E_BASE_URL to a running stack, such as http://localhost:18080');
}

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  forbidOnly: true,
  reporter: [['list']],
  outputDir: 'test-results',
  use: {
    baseURL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 900 } } }],
});
