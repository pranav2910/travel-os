import { defineConfig, devices } from '@playwright/test';

/**
 * Browser E2E against the REAL platform: the web edge (nginx + the services) and Keycloak.
 *   Docker stack:  E2E_BASE_URL=http://localhost:8080  E2E_KEYCLOAK_URL=http://localhost:8180  (make web-e2e)
 *   kind:          E2E_BASE_URL=http://localhost:18080 E2E_KEYCLOAK_URL=http://localhost:18180 (make web-e2e-kind)
 * Every run books on its own dates (the sandbox airline keeps cancelled flights cancelled).
 */
export default defineConfig({
  globalSetup: './e2e/global-setup.ts',
  testDir: './e2e',
  timeout: 240_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  workers: 1,
  retries: process.env['CI'] ? 1 : 0,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: process.env['E2E_BASE_URL'] ?? 'http://localhost:8080',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] }, testIgnore: /mobile\.spec\.ts/ },
    { name: 'mobile', use: { ...devices['Pixel 7'] }, testMatch: /mobile\.spec\.ts/ },
    { name: 'webkit-smoke', use: { ...devices['Desktop Safari'] }, testMatch: /smoke\.spec\.ts/ },
  ],
});
