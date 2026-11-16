# Playwright Jenkins Insights reporter

This optional package writes an enhanced manifest for the Playwright Insights Jenkins plugin. The plugin also imports Playwright's built-in JSON report directly, so most suites do not need this package. Use it when you need fuller step and assertion detail, comparison metadata, or literal redaction.

Add it to your Playwright configuration:

```ts
import { defineConfig } from '@playwright/test';

export default defineConfig({
  reporter: [
    ['list'],
    ['playwright-jenkins-insights', {
      outputFile: 'playwright-insights/manifest.json',
      comparisonKey: 'my-complete-suite'
    }]
  ]
});
```

Run Playwright, then import and archive the manifest in your Jenkins Pipeline:

```groovy
playwrightInsights results: 'playwright-insights/manifest.json'
archiveArtifacts artifacts: 'playwright-insights/manifest.json,test-results/**/*', allowEmptyArchive: true
```

See the [quickstart](https://github.com/krishnapollu/playwright-insights-jenkins/blob/main/docs/QUICKSTART.md) for setup, evidence handling, and limitations.
