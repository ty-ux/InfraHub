import { defineConfig } from '@playwright/test';

const baseURL = process.env.PLAYWRIGHT_BASE_URL || 'http://localhost:5173';

export default defineConfig({
  testDir: './tests/e2e',
  testMatch: ['**/monitor-notification-record-live.spec.ts'],
  timeout: 15_000,
  expect: {
    timeout: 5_000,
  },
  fullyParallel: false,
  retries: 0,
  reporter: 'line',
  workers: 1,
  use: {
    baseURL,
  },
});
