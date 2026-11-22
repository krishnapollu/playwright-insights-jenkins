# Playwright Insights for Jenkins — Product & Engineering Specification

**Status:** Draft v0.1  
**Date:** 2026-11-14  
**Working name:** Playwright Insights for Jenkins  
**Proposed license:** MIT (final decision before publishing)  
**Primary audience:** QA engineers, SDETs, test automation leads, CI maintainers  
**Repository model:** Monorepo with a Java/Jenkins plugin, an optional TypeScript reporter, JSON schema, fixtures, and documentation

**Implementation update:** The plugin now accepts Playwright's built-in JSON report and reads assertion steps from its built-in blob report. The reporter and versioned manifest described below remain an optional enhanced path; see [the quickstart](QUICKSTART.md) for the default setup.

> **Product promise:** Investigate failures, track flaky tests, and understand Playwright execution without leaving Jenkins.

## 1. Executive summary

Jenkins is effective at scheduling and orchestrating CI jobs; Playwright produces detailed, structured test outcomes. The typical Jenkins experience reduces test runs to a build status, a generic JUnit breakdown, and a link to an HTML report. This project creates a first-class Playwright experience in Jenkins without replacing Playwright's built-in reporter or Jenkins' existing test-reporting ecosystem.

The product consists of:

1. **A Jenkins plugin** (`playwright-insights`) that imports Playwright's built-in JSON report and presents Jenkins-native views, test-level history, and artifact links.
2. **An optional TypeScript Playwright reporter** (`playwright-jenkins-insights`) that writes a versioned manifest when richer step detail or literal redaction is needed.
3. **A shared schema and contract test suite** for the optional manifest path.

**MVP principle:** A useful, trustworthy failure investigation experience is more important than a large feature count. No AI or performance prediction is necessary for v1.

## 2. Problem, opportunity, and positioning

### 2.1 User problem

When a Playwright build fails, engineers often switch between the Jenkins console, JUnit results, Playwright HTML reports, traces, screenshots, and previous builds to answer basic questions:

- Which tests actually failed, and which passed after retry?
- Are these failures new, recurring, or historically flaky?
- Is the failure isolated to a particular Playwright project/browser?
- What was the failing step, error, duration, and relevant evidence?
- How did this run differ from a comparable previous run?

### 2.2 Existing alternatives

- **Jenkins JUnit plugin / Test Results Analyzer / Test Stability:** generic test results and trends; useful but do not necessarily preserve Playwright semantics.
- **HTML Publisher:** links to a generated Playwright HTML report; does not supply native Jenkins analysis.
- **Allure + Jenkins:** rich cross-framework reporting, but introduces a separate reporting format/toolchain.
- **Playwright HTML/JSON/blob reporters:** good run data and standalone views; not an integrated Jenkins build-history experience.

**Differentiator:** A small, Playwright-aware layer for Jenkins that preserves retry semantics, projects, attachments, and historical test identity. It **must interoperate** with JUnit, HTML Publisher, and Allure rather than attempting to replace them.

### 2.3 Target users

| Persona | Job to be done | Primary screen |
|---|---|---|
| Individual SDET | Find why a run failed | Build > Playwright Insights |
| Test lead | Identify new/recurring/flaky tests | Job > Trends |
| CI owner | Troubleshoot data ingestion / pipeline health | Build ingestion status |
| Team manager | Understand suite health across builds | Job-level summary |

## 3. Goals, measures, and non-goals

### 3.1 Product goals

1. Show an accurate **Playwright-native run summary** in Jenkins.
2. Reduce steps to investigate a failing test through evidence links and contextual details.
3. Compare tests across **comparable** runs, with explicit classifications and limitations.
4. Require minimal Jenkinsfile integration and no hosted external service.
5. Work under common Jenkins security and build-retention policies.

### 3.2 Success measures (proposed validation targets)

- **Setup:** working integration in <=15 minutes in a documented reference Jenkins instance (excluding plugin installation).
- **Coverage:** 100% of supported manifest statuses and retry outcomes displayed correctly in fixture tests.
- **Navigation:** <=3 clicks from build page to a failure and its attached trace/screenshot when present.
- **Import:** illustrative 5,000-test manifest imports reliably under the defined performance budget (see §12); benchmark before promising actual latency.
- **Adoption:** 5 external pilot Jenkins installations; collect qualitative evidence that the views are used during investigations.

These are **targets**, not claims of achieved performance or market demand.

### 3.3 Out of scope for MVP

- Executing Playwright tests, provisioning browsers, managing Node dependencies, or selecting workers.
- Replacing Jenkins JUnit or changing the job's build status automatically.
- Rerunning individual tests through the UI.
- AI diagnosis, automatic healing, PR review, Git integration beyond metadata already available.
- Organization-wide dashboards across independent Jenkins jobs.
- Browser-based Trace Viewer embedded inside Jenkins (download plus inline step details for MVP).
- Cross-CI support, distributed storage systems, or live streaming run telemetry.

## 4. Releases and priority

| Feature | Priority | MVP v0.1 | Later |
|---|---|---|---|
| Playwright JSON import; optional enhanced manifest | P0 | Yes | — |
| Pipeline step and failure-safe importer | P0 | Yes | — |
| Run counts: expected/unexpected/flaky/skipped | P0 | Yes | — |
| Per-project/browser breakdown | P0 | Yes | — |
| Test list, search/filter, details, errors, attempts | P0 | Yes | — |
| Safe Jenkins artifact links for evidence | P0 | Yes | — |
| Import errors and partial-data warnings | P0 | Yes | — |
| Compare with last comparable completed run | P1 | Yes, simple | More flexible comparisons |
| Last 10 comparable results and new/recurring classification | P1 | Yes, basic | Configurable windows |
| Deterministic failure categories | P1 | — | v0.2 |
| Flake rates, trends, duration regressions | P1 | — | v0.2 |
| Worker utilization, shard analytics, timeline | P2 | — | v0.3+ |
| AI insights and change correlation | P3 | — | Experimental opt-in |

## 5. Primary user journeys

### Journey A — Developer investigates a red run

1. Open a Jenkins build and click **Playwright Insights**.
2. See totals and project-level breakdown (e.g., Chromium failures vs Firefox).
3. Filter **Unexpected** or **Flaky** and open a test.
4. Read the error, attempt/retry sequence, duration, stack, and evidence.
5. Follow Jenkins-hosted trace/screenshot/video artifacts if available.
6. Compare with the prior comparable build and review recent outcomes.

### Journey B — Lead investigates emerging instability

1. Open a job's **Playwright Trends** page.
2. See recent run totals and select a recurring failing test.
3. Review its last ten comparable outcomes and whether it failed in the most recent run.
4. Use this evidence to prioritize triage; do not imply root cause.

### Journey C — Jenkins administrator integrates an existing pipeline

1. Install the Jenkins plugin.
2. Enable Playwright's built-in JSON reporter in the project configuration.
3. Invoke `playwrightInsights` in `post { always { ... } }`.
4. Archive result artifacts using standard Jenkins steps.
5. Verify the build displays an import success or explicit import warning.

## 6. Functional requirements (MVP)

### FR-01: Manifest emission

- TypeScript reporter supports Playwright Test's documented custom reporter interface.
- Produces **one manifest per logical Playwright execution** at reporter end, using an atomic temporary-file write/rename where supported.
- Records Playwright test project, file, title path, tags, annotations, status, attempts/retries, worker index, bounded steps and output, duration, errors, and evidence references.
- Redacts or truncates oversized content and never copies arbitrary attachment contents into the JSON.
- Supports configuration via reporter options or environment variables for output directory and optional metadata.
- Treats missing/malformed attachment paths as warnings, never as opportunities to access arbitrary files.

### FR-02: Pipeline import

- Jenkins exposes `playwrightInsights results: 'path/to/manifest.json'` as a Pipeline step.
- Accepts a **workspace-relative** manifest path or bounded Ant-style glob (if implemented, multiple-match semantics must be explicit).
- Pipeline import runs on an available agent/workspace, reads a bounded-size file, validates its schema and integrity, then attaches a serializable summary and detail data to the build.
- An import failure must **not silently mark the build green or overwrite test failure status**. Default: record an import warning and leave test-derived build status untouched; optional `failOnImportError: true` fails the step.
- Build action links appear only when a valid manifest was imported; otherwise an actionable diagnostic is logged.

### FR-03: Build summary

- Display final Playwright test statuses: **passed**, **failed**, **timed out**, **skipped**, **interrupted**. Show a clear route from a build to its job dashboard.
- Keep Playwright outcomes as separate comparison signals: `expected` (met the expected status, including an expected failure), `unexpected` (did not meet expectation, including an unexpected pass), `flaky` (retries led to an expected result), `skipped`.
- Show total tests, final status distribution, wall-clock duration, recorded assertion steps, project breakdown, and imported timestamp.
- Distinguish **attempt counts** from **logical test counts** to avoid double counting retries.
- If results are incomplete or unsupported, show **partial/unknown** rather than fabricating a pass count.

### FR-04: Test drill-down

- Paginated or server-side limited test list with filters for final status, project, and free-text title/file.
- Test detail displays title path, source file and line, project, expected status, final status, outcome context, tags, annotations, ordered attempts, retry indices, worker index, steps, durations, recorded assertion counts, bounded output, errors, stack, and evidence.
- Show evidence only when referenced attachments are actually archived and accessible.
- If a test was not collected or no attempt data is available, show an explicit notice.

### FR-05: History and comparison

- Find the **previous comparable completed build** in the same Jenkins job (definition in §9).
- Show new failures, continuing failures, recovered tests, and newly observed tests against that baseline, with separate **unclassified** for missing/incomparable data.
- Show up to ten prior comparable logical results for the selected test; no cross-browser conflation.
- A result is **flaky within run** only if Playwright reports `flaky`; historical intermittency is separately described, not conflated.
- History should degrade gracefully if previous builds were discarded by Jenkins retention.

### FR-06: Artifact evidence

- Reporter serializes **relative attachment paths and metadata only**; Jenkins plugin never treats raw paths as trusted downloadable URLs.
- Documentation provides `archiveArtifacts` example for `trace.zip`, screenshots, and videos.
- Render inline images and video only for validated archived artifacts with matching safe media types and extensions. Keep other files as downloads. Validate paths against the build's archived artifact inventory, including path traversal and symlink protections.
- Respect Jenkins permission checks; do not create anonymous bypass routes.

### FR-07: Observability

- Record manifest version, counts, size, import outcome, warning count, and import time in Jenkins logs/administrative diagnostics.
- No secrets, environment-variable dumps, request bodies, or unredacted private tokens in diagnostics.

## 7. Detailed UI / information architecture

### 7.1 Build > Playwright Insights

```text
Playwright Insights — Build #183
[Unexpected 19] [Flaky 12] [Expected 817] [Skipped 7]
Duration: 38m 14s    Projects: Chromium, Firefox
Compared with: #182 (same branch + suite + project set)
New failures: 4   Continuing failures: 8   Recovered: 3

Project       Expected  Unexpected  Flaky  Skipped
Chromium         400       10          8       3
Firefox          417        9          4       4

[All] [Unexpected] [Flaky] [Search tests...]
Test title                 Project   Outcome     Duration
Booking / create booking   Chromium  Unexpected   48.2s
Customs / submit          Firefox   Flaky        17.1s
```

**Numbers above are illustrative mock data, not measured results.** Logical categories must sum to total tests.

### 7.2 Test detail

- Context: project/browser, file:line, title path, expected status, classification.
- Attempt timeline: initial attempt + retries, each with status, duration, worker, steps, output, error/stack, and inline media when available.
- History strip: last ten *comparable* runs with date/build link and explicit missing/unknown markers.
- Comparison: prior outcome, duration difference if meaningful, and prior evidence links where retained.

### 7.3 Job > Playwright Insights

- v0.1: chart and ledger of the last ten comparable runs, totals, links to each build; test history accessible via a selected build.
- v0.2: pass/flake trends, persistent failures, slow tests, groupings, and richer comparative charts.

### 7.4 Accessibility

- Keyboard navigable, readable screen-reader labels, meaningful non-color text statuses, responsive tables.
- Use Jenkins-supported design system, components, and permission patterns. Avoid untrusted HTML rendering.

## 8. System architecture

```text
Playwright Test runner
   │
   ▼
TypeScript reporter (npm)
   ├─ collects run + logical tests + attempts
   ├─ serializes evidence references
   └─ writes insights-manifest.json (versioned)
             │
             ▼
Jenkins workspace / agent
   ├─ playwrightInsights(results: ...)
   │        │
   │        ▼
   │   Jenkins plugin (Java)
   │      ├─ schema validation + limits
   │      ├─ immutable build snapshot
   │      ├─ RunAction + views
   │      └─ same-job historical lookup
   └─ archiveArtifacts(...) for evidence
```

### 8.1 Repository

```text
playwright-insights/
├── pom.xml                    # Jenkins plugin build and release metadata
├── src/                       # Java sources, Jelly views, tests, CSS
├── reporter/                  # TypeScript Playwright custom reporter
├── schema/                    # JSON Schema, example manifests, compatibility policy
├── docs/                      # Quickstart and product specification
├── Jenkinsfile                # Jenkins plugin CI
├── LICENSE
└── README.md
```

### 8.2 Architecture decisions

- **No separate database in v0.1.** Keep bounded run snapshots attached to Jenkins builds. A larger global index is deferred.
- **No network calls from the plugin by default.** Offline/on-premises friendly.
- **Data snapshots immutable after import.** Historical views derive from surviving Jenkins builds and are retention-aware.
- **Stable intermediate contract.** The TypeScript package can evolve independently of the Java plugin.
- **Single run initially.** Multiple invocations in one build are not merged invisibly: v0.1 either accepts a declared `runId` per import or rejects duplicates with clear guidance; define final UI behavior before implementation.

## 9. Data model and identity

### 9.1 Versioned manifest (illustrative contract)

```json
{
  "schemaVersion": "1.0.0",
  "producer": { "name": "playwright-jenkins-insights", "version": "0.1.0" },
  "run": {
    "id": "run-unique-id",
    "startedAt": "2026-11-14T20:00:00.000Z",
    "durationMs": 120000,
    "complete": true,
    "comparisonKey": "optional-normalized-suite-key",
    "metadata": { "branch": "develop", "commit": "d947f8a" }
  },
  "projects": [{ "id": "chromium", "name": "chromium" }],
  "tests": [{
    "id": "stable-test-key",
    "projectId": "chromium",
    "file": "tests/booking.spec.ts",
    "line": 12,
    "titlePath": ["Booking", "creates a booking"],
    "expectedStatus": "passed",
    "outcome": "flaky",
    "attempts": [
      {
        "retry": 0,
        "status": "failed",
        "durationMs": 30000,
        "errors": [{ "message": "Timeout...", "stack": "..." }],
        "attachments": [{ "name": "trace", "contentType": "application/zip", "path": "test-results/booking/trace.zip" }]
      },
      { "retry": 1, "status": "passed", "durationMs": 6000, "errors": [], "attachments": [] }
    ]
  }],
  "warnings": []
}
```

This is a **proposed** normalized schema, not Playwright's native JSON reporter format. Before coding, freeze v1.0.0 into JSON Schema with required fields, enums, maximum sizes, examples and schema validation tests.

### 9.2 Stable identity

- Use a normalized tuple of **project identity + repository-relative file path + title path + repeat-each index**; hash it for an opaque stable key. Validate uniqueness in a run.
- Do **not** use Jenkins build number, test runtime duration, retry index, or source line as the primary identity.
- Playwright's own test ID may be included as optional diagnostic metadata; do not assume it is stable across major upgrades without validation.
- Renaming a test or moving its file produces a new identity unless a later explicit aliasing system is added.
- Different browser/project configurations must not be merged into a single test history.
- Preserve parameterized test distinctions through title path, project, and repeat index.

### 9.3 Comparison eligibility

A prior build is comparable only if:

1. It belongs to the same Jenkins job (including multibranch branch-job identity).
2. It contains a complete valid manifest under a compatible schema major version.
3. It shares the same declared comparison key (or, by default, the same normalized project set plus a stable suite identifier supplied by configuration).
4. The selected test's stable identity matches for per-test history.

A changed test selection (`--grep`, spec subset, changed shard count) may make suite-level count comparisons misleading. Default v0.1 behavior: show a **comparison warning** or disable suite-level deltas if selection metadata differs; test-level comparisons may remain valid if identities match. Never treat tests absent from a partial run as recovered or deleted.

### 9.4 Classifications

- **New failure:** comparable previous run has this test in an expected/non-failing state, latest is unexpected.
- **Continuing failure:** latest and previous comparable run are both unexpected.
- **Recovered:** previous comparable run unexpected, latest expected; distinguish flaky recovery if latest outcome is flaky.
- **Newly observed:** test is present now and absent from prior comparable full run.
- **Unclassified:** no baseline, missing data, identity changes, or non-comparable selection.
- **Historically intermittent** (v0.2): mixed expected and unexpected outcomes within a stated historical window, reported separately from within-run Playwright `flaky`.

## 10. Jenkins integration contract

### 10.1 Reporter configuration (illustrative)

```ts
// playwright.config.ts
import { defineConfig } from '@playwright/test';

export default defineConfig({
  reporter: [
    ['list'],
    ['@your-scope/playwright-jenkins-insights', {
      outputFile: 'playwright-insights/manifest.json'
    }]
  ]
});
```

The package name and options are **provisional** until implementation.

### 10.2 Declarative Pipeline example

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
      // Same workspace used for the test execution is required.
      playwrightInsights results: 'playwright-insights/manifest.json',
                         failOnImportError: false
      archiveArtifacts artifacts: 'playwright-insights/manifest.json,test-results/**/*',
                       allowEmptyArchive: true
    }
  }
}
```

**Important:** Jenkins may skip later statements if the import step throws. The plugin defaults to non-fatal validation/logging; users who choose strict import failure should place archiving in `catchError`/`try-finally` as needed. Parallel/sharded pipelines require explicit per-branch manifest names and a future merge step; they are not implicitly supported by this sample.

### 10.3 Build result policy

- The existing `sh 'npx playwright test'` process exit status controls the Jenkins stage/build under normal Jenkins behavior.
- Importing results **does not set SUCCESS, UNSTABLE, or FAILURE** in MVP.
- Optionally in v0.2, expose explicit, documented quality gates under separate controls; never surprise users by changing Jenkins result semantics.

### 10.4 Compatibility matrix (to verify during prototype)

- Jenkins core: select a **currently supported LTS baseline** when development starts; record the exact minimum in plugin metadata.
- Java: use the version required by the chosen Jenkins baseline and parent POM.
- Playwright: choose and test a declared version range; avoid claiming that all Playwright versions work.
- Node: support the Node LTS versions supported by the selected Playwright releases.
- Execution: Linux/macOS/Windows agents where path normalization and artifact archiving are validated.
- Jenkins: Pipeline first; freestyle publisher only if pilot demand warrants it.

## 11. Failure modes and edge cases

| Scenario | Expected behavior |
|---|---|
| Tests fail, reporter finishes | Manifest imported; failures displayed; existing Jenkins status unchanged |
| Runner process killed before reporter flush | Missing/incomplete manifest warning; never show zero failures as a pass |
| Invalid JSON or unsupported schema major | Reject and log actionable error; no corrupted build action |
| One test has multiple retries | Count one logical test, show all attempts |
| Expected failure passes unexpectedly | Preserve Playwright's `unexpected` semantic |
| Duplicate stable keys | Validation error or explicit identity disambiguation; never overwrite silently |
| Historical build deleted | History shortens with retention notice |
| Screenshot path not archived | Render unavailable evidence notice, no broken pseudo-link |
| Sharded execution | Each shard produces separate manifest; merging deferred or explicitly supported |
| Multibranch Jenkins | History isolated to branch job unless deliberate cross-branch comparison is added |
| Huge manifests or stack traces | Enforce limits, truncate with warning, avoid rendering large payloads at once |
| No Playwright tests found | Show empty/zero-tests state distinct from a successful populated run |
| Parallel pipeline branches | Avoid workspace collisions and define run identity per branch |

## 12. Storage, performance, and retention

- Default maximum imported manifest: **25 MiB** (configurable within administrator-controlled ceiling); benchmark large suites before finalizing.
- Default maximum tests: **20,000**; maximum attempts per test and message length must also be bounded at schema/parser level.
- Stream/parse carefully and reject unsafe payloads before persisting. Avoid retaining a full duplicated graph in Jenkins memory per page request.
- Paginate test list; cap history traversals to recent surviving builds (configurable maximum, initially 50 examined builds for 10 comparable results).
- Store bounded metadata under the build's Jenkins data directory, not a custom permanent global DB. Artifacts stay in Jenkins' standard artifact store and follow normal retention.
- Jenkins build discard policies are the source of truth; the UI must be explicit that history is not a permanent analytics database.
- Conduct synthetic benchmark runs with 100, 1,000, 5,000, and 20,000 tests. Record import CPU/memory, page latency, and persisted size before establishing release SLAs.

## 13. Security and privacy

- Treat all manifest strings, error stacks, annotations and paths as **untrusted** user-controlled data.
- Escape output; never render test messages as executable HTML/Markdown without sanitization.
- Validate relative paths, reject absolute paths, `..` traversal, URL schemes and unsafe encodings; check against archived build artifacts before linking.
- Enforce Jenkins `Item.READ`/`Run` visibility rules and applicable artifact access permissions; do not expose other jobs or builds.
- Use Jenkins-supported form validation, descriptors, CSRF handling, and Stapler routing. Avoid arbitrary user-controlled file reads from controller or agents.
- Reporters may capture sensitive URLs, request details, and stack values. Document repository/test responsibility for not putting secrets into titles or attachments; add configurable redaction for selected patterns and bounded preview lengths.
- No outbound telemetry by default; later external integrations must be disabled unless explicitly enabled by an administrator.
- Dependabot/Renovate-style dependency updates, security advisories, and plugin security review are release prerequisites.

## 14. Suggested implementation workstreams

### Workstream A — Contract & reporter (TypeScript)

- Define JSON schema v1 and sample fixtures: clean run, fail, flaky, expected fail, unexpected pass, skipped, retries, attachments, no tests, missing manifest.
- Implement Playwright custom reporter; add fixture-based Playwright integration tests.
- Normalize paths across OSes and ensure write-on-end behavior.
- Publish private canary package before public npm release.

### Workstream B — Jenkins importer (Java)

- Start from current Jenkins plugin archetype and plugin parent BOM.
- Implement Pipeline Step + step descriptor, agent-side workspace read, schema validation, bounded data persistence and RunAction.
- Test with Jenkins Test Harness and Pipeline unit/integration fixtures.
- Verify permissions and error handling before polishing UI.

### Workstream C — UI & history

- Jenkins design-system build summary and test list/details.
- Same-job baseline lookup with comparable-run rules.
- Secure, validated archived artifact links and empty/error states.

### Workstream D — Delivery & documentation

- npm and Maven build/release automation.
- Compatibility matrix, quickstart, sample test repository, Dockerized local Jenkins demo, screenshots/GIFs.
- Pilot feedback process, issue templates, contribution guide, security policy.

## 15. MVP acceptance criteria

The MVP is **done** when all of the following are true:

- [ ] A documented Playwright configuration produces a manifest from a passing, failing, flaky, and skipped run.
- [ ] Manifest validates against published JSON Schema v1 and rejects malformed or oversized input.
- [ ] Jenkins Pipeline can import it via `playwrightInsights` and show a build-level action.
- [ ] Logical totals, project breakdown, retry attempts, and per-test details match Playwright source fixtures.
- [ ] A failing test can show archived screenshots and video inline and provide a trace download safely when available.
- [ ] Historical comparison correctly handles new, continuing, recovered and unclassified test cases.
- [ ] Missing/partial input does not appear as a successful zero-failure run.
- [ ] Build result is not silently modified by importer.
- [ ] Plugin UI passes authorization and XSS/path traversal tests.
- [ ] All user-facing data views have meaningful empty states and keyboard-accessible controls.
- [ ] Build summary, test detail, and trends pass a visual and usability review at desktop and mobile widths, including Jenkins light and dark themes.
- [ ] Docs explain JUnit and HTML Publisher coexistence, sharding limitation, and Jenkins retention impact.
- [ ] End-to-end demo works on a pinned Jenkins LTS + declared Playwright and Node versions.

## 16. Roadmap

### Phase 0 — Technical spike (before substantial UI work)

**Exit criteria:** create a 10-test Playwright suite with retries and attachments; generate a schema-validated manifest; import it into an ephemeral Jenkins plugin test instance; render one build summary page. Validate exact Playwright reporter lifecycle behavior, attachment path mapping, and Jenkins Pipeline APIs.

### Phase 1 — MVP / v0.1

Ship reporter, manifest contract, import step, native build summary, details, evidence links, minimal previous-run comparison and last-ten histories. Provide sample repo and installation guide.

**Milestone 1A — Correctness and security:** complete the MVP acceptance tests for import behavior, comparison, authorization, XSS, and artifact paths.

**Milestone 1B — Next-generation UI:** give Playwright Insights a distinct visual identity; redesign the build summary, test explorer, details, and job history around outcome and history charts, scannable results, an attempt and step timeline, and inline screenshots and video. Keep the experience inside Jenkins, responsive and keyboard accessible. Review the three primary user journeys on desktop and mobile; a developer should reach a failure and its evidence within three clicks. Full Trace Viewer embedding remains deferred until it can work with authenticated Jenkins artifacts and content security policy.

**Milestone 1C — Public alpha:** validate a real Pipeline demo, package the reporter and plugin, finish installation docs, and gather pilot feedback. The UI milestone is a release gate for this alpha.

### Phase 2 — Reliability / v0.2

Deterministic failure categories, historical flake indicators, duration statistics, better filtering, and configurable history windows. Consider optional quality gates separately.

### Phase 3 — Performance / v0.3

Optional richer telemetry: worker timeline, concurrency metrics, slow fixtures and tests, shard aggregation. Validate instrumentation and ensure it does not impose excessive runtime overhead. Perfetto compatibility is exploratory, not an MVP dependency.

### Phase 4 — Intelligence / experimental

Optional AI explanations, repeated-failure clustering, commit/change correlation, diagnostic suggestions and cross-job analytics. Strictly opt-in, privacy reviewed, with clear confidence/evidence and no unsupported causation.

## 17. Market validation / launch plan

1. Publish a short **problem-focused demo** comparing Jenkins generic results vs the same run in Playwright Insights.
2. Interview at least 5 users/teams using Playwright on Jenkins; ask what they inspect after a red build and which evidence they need most.
3. Validate **willingness to install a Jenkins plugin** separately from enthusiasm for screenshots and dashboards.
4. Pilot against small and large test suites, parallel jobs and real artifact retention policies.
5. Public alpha in GitHub + npm first; apply for official Jenkins plugin hosting/publication after security and compatibility reviews.
6. Measure install-to-first-insight, import reliability, most-used view, and whether users return to previous-run comparison.

**Go/no-go after pilot:** Continue if teams use the plugin during real failure triage and can articulate value beyond Playwright HTML report + JUnit; reconsider positioning if only cosmetic dashboards attract interest.

## 18. Key risks and mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| Existing tools already satisfy user needs | Weak adoption | Pilot workflow-based comparison, not feature checklist alone |
| Playwright reporter API/format changes | Broken ingestion | Own versioned schema; pinned integration tests |
| Jenkins plugin maintenance/security burden | Release friction | Narrow APIs, current LTS, Jenkins tooling, strict permission tests |
| Large runs overload controller | Stability issue | Data limits, pagination, bounded history, benchmarks |
| Shards/parallel runs mismatch | Wrong counts | Explicitly defer merge or implement verified merge protocol |
| Misleading history after test rename/selection changes | Trust erosion | Stable identity rules and strong comparability warnings |
| Sensitive data in errors/attachments | Security/privacy | Redaction limits, Jenkins permissions, no outbound transfer |
| Trace links fail due to archiving paths | Poor UX | Explicit attachment mapping, archive validation, docs |

## 19. Open design decisions

1. **Project brand and package scope:** verify availability and avoid naming conflicts before publishing.
2. **Manifest format:** one aggregate JSON file vs multiple run-chunk files; start with one bounded file.
3. **Multiple invocations per build:** one named logical run per import vs a build-level aggregation UI.
4. **Shards:** release limitation vs v0.1 optional merge command; default recommendation is to defer until robust aggregation exists.
5. **Snapshot persistence:** Jenkins `RunAction` XML vs a managed sidecar JSON file with a compact summary; prototype and benchmark both.
6. **Comparison key derivation:** explicit suite key recommended; define robust defaults for branch and test selection.
7. **Strict import mode:** preserve stage reporting and artifact archiving even when an import fails.
8. **Plugin display name:** Playwright Insights vs Playwright Run Insights.

## 20. Immediate next steps

1. Freeze **schema v1** with representative fixtures and validation rules.
2. Build the **Phase 0 spike** end-to-end (reporter → manifest → Jenkins Pipeline step → summary view).
3. Validate one real local Playwright suite and one separate Jenkins controller.
4. Resolve artifact link security and history comparability before building advanced analytics.
5. Publish an MVP demo and collect feedback before expanding scope.

---

### Reference starting points (implementation verification)

- Playwright reporter API: https://playwright.dev/docs/api/class-reporter
- Playwright Test reporters: https://playwright.dev/docs/test-reporters
- Playwright test results/outcomes: https://playwright.dev/docs/api/class-testresult and https://playwright.dev/docs/api/class-testcase
- Jenkins plugin development: https://www.jenkins.io/doc/developer/plugin-development/
- Jenkins Pipeline steps: https://www.jenkins.io/doc/book/pipeline/syntax/
- Jenkins JUnit plugin: https://plugins.jenkins.io/junit/
- Jenkins HTML Publisher: https://plugins.jenkins.io/htmlpublisher/
- Jenkins Allure plugin: https://plugins.jenkins.io/allure-jenkins-plugin/

**Note:** The architecture and product concepts here are proposals. Before implementation, verify current API behavior, minimum supported versions, and plugin ecosystem overlap against live docs and a technical spike.
