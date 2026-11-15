# Local quickstart

This guide uses the local reporter package and Jenkins plugin build. The reporter package is not published to npm yet.

## Playwright project

From this repository, run `npm ci && npm run build:reporter && npm pack ./reporter`. In your Playwright project, install the resulting `.tgz` as a dev dependency. The reporter currently targets Playwright Test 1.64.x and Node 24; support for other versions has not been verified.

Add the reporter alongside any existing console, JUnit, or HTML reporters:

```ts
import { defineConfig } from '@playwright/test';

export default defineConfig({
  reporter: [
    ['list'],
    ['playwright-jenkins-insights', {
      outputFile: 'playwright-insights/manifest.json',
      comparisonKey: 'my-complete-suite',
      metadata: { selection: 'all' },
      redact: ['a-known-secret-value']
    }]
  ]
});
```

The comparison key must identify the same full suite across builds. Give filtered or sharded runs different keys, or omit the key to disable comparisons. The reporter writes one manifest at the end of a logical Playwright execution. A killed runner may leave no manifest.

`redact` replaces configured literal strings in titles, annotations, steps, output previews, errors, stacks, and metadata. It is not a general secret scanner. The reporter omits in-memory attachment bodies; archive file-backed traces, screenshots, and videos to make evidence available.

## Jenkins

Build the plugin from the repository root with JDK 21 and Maven: `mvn verify`. The POM targets Jenkins 2.568.3. Install the generated `target/playwright-insights.hpi` in a compatible Jenkins controller with Pipeline support.

For a local UI preview, run `mvn hpi:run -Dport=8092` from the repository root, then open `http://localhost:8092/jenkins/`. This starts a development Jenkins controller with the plugin loaded. Its local state lives in `work/`; stop it with Ctrl+C. The preview avoids a permanent Jenkins installation.

Run `bash scripts/phase0.sh` to execute the real six-test Playwright fixture and import its generated manifest into an ephemeral Jenkins Test Harness controller. The fixture intentionally produces a nonzero Playwright exit status.

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
      playwrightInsights results: 'playwright-insights/manifest.json'
      archiveArtifacts artifacts: 'playwright-insights/manifest.json,test-results/**/*',
                       allowEmptyArchive: true
    }
  }
}
```

The import step logs a warning and leaves the build result unchanged if the manifest is missing or invalid. `failOnImportError: true` makes the step fail; place archiving in `try/finally` or `catchError` in that mode. The manifest path must be relative to the workspace used by the test execution. One manifest import per build is supported. Parallel branches and shards need separate builds; manifest merging is not available yet.

Archived PNG, JPEG, WebP, WebM, and MP4 evidence appears inline in the test detail when its declared type matches its file extension. Other files remain downloads. Trace steps and errors appear inline; the full Playwright Trace Viewer is not embedded because its hosted version may be blocked by Jenkins content security policy or cross-origin rules for authenticated artifacts. Download the trace and run `npx playwright show-trace trace.zip` for the complete viewer. Jenkins access rules and build retention apply to the manifest snapshot and artifacts. The reporter records error text and paths; ensure test names, output, annotations, and attachments contain no secrets.

The run view shows final Playwright statuses and links back to the job dashboard. Durations include all attempts for a test. “Recorded assertions” counts `expect` steps present in the manifest, including retries; older manifests or truncated step lists may show fewer than the tests actually executed.
