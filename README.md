<h1><img src="docs/images/logo.svg" alt="" width="44" height="44"> Playwright Insights for Jenkins</h1>

Playwright test results inside Jenkins, with run history, test details, and archived evidence in one place. The plugin imports Playwright's built-in JSON report after a Pipeline build and adds **Playwright Insights** to the job and build pages. No extra reporter package is required in your test suite.

![Playwright Insights job dashboard showing six runs, summary metrics, and a trend chart](docs/images/dashboard.png)

## What you can inspect

- **Job dashboard:** recent runs, test counts, elapsed time, and the share of tests needing review.
- **Build results:** final Passed, Failed, Timed out, Skipped, and Interrupted statuses; project breakdown; and changes from the previous comparable run.
- **Test details:** search and filter results, review earlier outcomes, retries, available Playwright steps, failures, output, and annotations.
- **Evidence:** view archived screenshots and videos inline. Trace steps and errors appear in the test detail; download the trace archive to open the full Playwright Trace Viewer locally.

### Build results

![Build results with status summary, project breakdown, run comparison, and test explorer](docs/images/run.png)

### Test evidence

![Failed test detail with attempts, steps, screenshot, video, and trace download](docs/images/test-evidence.png)

These screenshots come from the local six-run demo using the optional enhanced reporter. Playwright's built-in JSON report may contain fewer step and assertion details; the sample results and timestamps are illustrative.

## Install from source

The plugin is not published yet. You need JDK 21 and Maven to build it. The plugin targets Jenkins 2.568.3 with Pipeline support and has been tested with Playwright Test 1.64.x.

1. Build the plugin from this repository:

   ```sh
   mvn verify
   ```

2. In Jenkins, go to **Manage Jenkins → Plugins → Advanced**, upload `target/playwright-insights.hpi` under **Deploy Plugin**, and restart the controller. This follows the [Jenkins manual plugin installation guide](https://www.jenkins.io/doc/book/managing/plugins/#from-the-web-ui).

For a local preview without installing into an existing controller, run `mvn hpi:run -Dport=8092` from this repository and open `http://localhost:8092/jenkins/`.

## Configure Playwright and Jenkins

Enable Playwright's built-in JSON reporter in your project's `playwright.config.ts`:

```ts
import { defineConfig } from '@playwright/test';

export default defineConfig({
  reporter: [
    ['list'],
    ['json', { outputFile: 'playwright-insights/results.json' }]
  ],
  use: {
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    trace: 'retain-on-failure'
  }
});
```

Add a `Jenkinsfile` to that project. The test command and import step must use the same workspace; archive the JSON report and Playwright attachments so evidence appears in Jenkins:

```groovy
pipeline {
  agent any
  stages {
    stage('Playwright') {
      steps {
        sh 'npm ci'
        sh 'npx playwright test'
      }
    }
  }
  post {
    always {
      playwrightInsights results: 'playwright-insights/results.json',
                         comparisonKey: 'my-complete-suite'
      archiveArtifacts artifacts: 'playwright-insights/results.json,test-results/**/*',
                       allowEmptyArchive: true
    }
  }
}
```

Open **Playwright Insights** on the Jenkins job for run history, or on a build for its test results. `comparisonKey` groups equivalent full-suite runs for comparisons; use a different key for filtered runs or omit it. See the [local quickstart](docs/QUICKSTART.md) for import behavior, evidence handling, and limitations. The [manifest contract](schema/README.md) describes the plugin's internal snapshot format.

## Project status

The Jenkins plugin is prepared for public hosting but is not yet released to the Jenkins Update Center. The separate reporter in this repository remains available for richer step detail and redaction, but is optional. See the [publishing checklist](docs/PUBLISHING.md) for the remaining steps.

Licensed under [MIT](LICENSE).
