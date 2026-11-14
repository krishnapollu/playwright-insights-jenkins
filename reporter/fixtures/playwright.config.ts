import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: '.',
  testMatch: 'sample.spec.ts',
  workers: 1,
  retries: 1,
  reporter: [['../../reporter/index.ts', { outputFile: process.env.PLAYWRIGHT_INSIGHTS_OUTPUT_FILE }]],
  outputDir: '../../test-results'
});
