# Playwright Jenkins Insights reporter

This package writes the manifest consumed by the Playwright Insights Jenkins plugin.

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
