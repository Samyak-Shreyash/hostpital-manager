import { defineConfig } from '@playwright/test'

// The M3 and M4 journeys against the REAL Compose stack ([E7-M3]): no mocks, no dev server. Prerequisites and env vars: README.md.
// The default config (playwright.config.ts) ignores these specs, so `npm run test:e2e` stays a mocked, backend-free run.
export default defineConfig({
  testDir: './tests',
  testMatch: ['m3-opd-journey.spec.ts', 'm4-ipd-journey.spec.ts'],
  reporter: 'list',
  workers: 1,
  retries: 0,
  timeout: 90_000,
  use: {
    baseURL: (process.env.E2E_BASE_URL ?? 'http://localhost:8080').replace(/\/$/, ''),
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'real-stack', use: { browserName: 'chromium' } }],
})
