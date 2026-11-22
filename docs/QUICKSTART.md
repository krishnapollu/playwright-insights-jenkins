# Local quickstart

Playwright Insights imports Playwright's built-in JSON report and reads `expect()` steps from its built-in blob report. Your test suite does not need an additional reporter package.

## Playwright project

Keep your existing reporters and add `json` in `playwright.config.ts`:

```ts
import { defineConfig } from '@playwright/test';

export default defineConfig({
  reporter: [
    ['list'],
    ['json', { outputFile: 'playwright-insights/results.json' }],
    ['blob', { outputFile: 'playwright-insights/results.zip' }]
  ],
  use: {
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    trace: 'retain-on-failure'
  }
});
```

The JSON report must include the full suite. Leave the JSON reporter's `onlyFailures` option off. The `use` settings are examples for capturing failure evidence; keep your existing capture settings if they already produce the files you need.

## Jenkins

Build the plugin from the repository root with JDK 21 and Maven: `mvn verify`. The POM targets Jenkins 2.568.3. Upload `target/playwright-insights.hpi` through **Manage Jenkins → Plugins → Advanced → Deploy Plugin**, then restart Jenkins. Jenkins documents [manual plugin installation](https://www.jenkins.io/doc/book/managing/plugins/#from-the-web-ui).

For a local UI preview, run `mvn hpi:run -Dport=8092` from the repository root, then open `http://localhost:8092/jenkins/`. This starts a development Jenkins controller with the plugin loaded. Its local state lives in `work/`; stop it with Ctrl+C.

Add the import step to your Playwright project's `Jenkinsfile`:

```groovy
pipeline {
  agent any
  options { buildDiscarder(logRotator(numToKeepStr: '20')) }
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
                         blob: 'playwright-insights/results.zip',
                         comparisonKey: 'my-complete-suite'
      archiveArtifacts artifacts: 'playwright-insights/results.json,playwright-insights/results.zip,test-results/**/*',
                       allowEmptyArchive: true
    }
  }
}
```

The test command and import step must use the same workspace. `comparisonKey` groups equivalent full-suite runs for comparisons; give filtered runs a different key or omit it. The import step logs a warning and leaves the build result unchanged if the JSON report is missing or invalid. `failOnImportError: true` makes the step fail; place archiving in `try/finally` or `catchError` in that mode. One report import per build is supported. Parallel branches and shards need separate builds; report merging is not available yet.

The job dashboard links to **Spec history**, which shows spec health and each test's outcomes across retained comparable builds. The example keeps 20 builds; Jenkins build deletion removes those outcomes from this history. Runs without a stable `comparisonKey`, incomplete runs, and runs with a different project set or selection are excluded from the comparison.

The dashboard's **Retry-flaky tests** count comes from Playwright's `flaky` outcome in the latest run: a test passed after a retry. On the spec page, **With retry passes** counts spec files containing that outcome anywhere in retained history. **With changing tests** separately counts spec files containing a test that met expectation in some retained builds and missed it in others. The same spec can appear in both counts; a spec file itself has no Playwright flaky status.

Archive `test-results/**/*` so file-backed images, videos, and traces remain available. The plugin also writes in-memory image and video attachments from the JSON report into `test-results/playwright-insights/` during import. PNG, JPEG, WebP, WebM, and MP4 evidence appears inline when the declared type matches the file extension. Other files remain downloads. The full Playwright Trace Viewer is not embedded; download the trace and run `npx playwright show-trace trace.zip` for the complete viewer.

The built-in JSON report contains statuses, retries, durations, errors, output, annotations, attachments, and user-defined `test.step` entries. The blob report supplies `expect()` steps and assertion counts. If the blob is missing or from a different run, the JSON results still import but assertion detail is marked unavailable. The optional [enhanced reporter](../reporter/README.md) writes the plugin's versioned manifest with more API step detail and literal redaction. Both formats are accepted by `playwrightInsights`.

The reporter records error text and paths. Ensure test names, output, annotations, and attachments contain no secrets. Jenkins access rules and build retention apply to the report snapshot and archived artifacts.

Run `bash scripts/phase0.sh` to exercise a six-test fixture with both report formats in the Jenkins Test Harness. The fixture intentionally produces a nonzero Playwright exit status.
