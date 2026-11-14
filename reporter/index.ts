import { createHash, randomUUID } from 'node:crypto';
import { existsSync, mkdirSync, realpathSync, renameSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, relative, resolve, sep } from 'node:path';
import type { FullConfig, FullResult, Reporter, Suite, TestCase } from '@playwright/test/reporter';

type Options = {
  outputFile?: string;
  comparisonKey?: string;
  metadata?: { branch?: string; commit?: string; selection?: string };
  redact?: string[];
};

const MAX_TESTS = 20_000;
const MAX_WARNINGS = 100;
const MAX_BYTES = 25 * 1024 * 1024;
const safePath = /^(?!\.{1,2}(?:\/|$))[A-Za-z0-9_@()+, .=-]+(\/(?!\.{1,2}(?:\/|$))[A-Za-z0-9_@()+, .=-]+)*$/;

function clipped(value: string, length: number): string {
  return value.length <= length ? value : value.slice(0, length);
}

export default class JenkinsInsightsReporter implements Reporter {
  private config?: FullConfig;
  private suite?: Suite;
  private startedAt = new Date();
  private readonly options: Options;
  private readonly warnings: string[] = [];
  private partial = false;

  constructor(options: Options = {}) {
    this.options = options;
  }

  printsToStdio(): boolean { return false; }

  onBegin(config: FullConfig, suite: Suite): void {
    this.config = config;
    this.suite = suite;
    this.startedAt = new Date();
  }

  private warn(message: string): void {
    if (this.warnings.length < MAX_WARNINGS - 1) this.warnings.push(message);
    else if (this.warnings.length === MAX_WARNINGS - 1) this.warnings.push('Additional warnings omitted');
  }

  private incomplete(message: string): void {
    this.partial = true;
    this.warn(message);
  }

  private preview(value: string, length: number): string {
    let text = value;
    for (const secret of this.options.redact || []) {
      if (secret) text = text.replaceAll(secret, '[REDACTED]');
    }
    if (text.length > length) this.warn(`Text truncated to ${length} characters`);
    return clipped(text, length);
  }

  private relativePath(path: string): string | undefined {
    if (!path || !existsSync(path)) return undefined;
    try {
      const root = realpathSync(process.cwd());
      const full = realpathSync(path);
      const value = relative(root, full).split(sep).join('/');
      return value.length <= 1024 && safePath.test(value) ? value : undefined;
    } catch {
      return undefined;
    }
  }

  async onEnd(result: FullResult): Promise<void> {
    if (!this.config || !this.suite) return;
    const tests = this.suite.allTests();
    const names = [...new Set(tests.map(test => test.parent.project()?.name || 'default'))];
    if (names.length > 128 || names.some(name => name.length > 128)) {
      process.exitCode = 1;
      console.error('Playwright Insights: project identities exceed manifest limits');
      return;
    }
    const projects = names
      .map(name => ({ id: clipped(name, 128), name: clipped(name, 256) }));
    const records = tests.slice(0, MAX_TESTS).map(test => this.record(test));
    if (tests.length > MAX_TESTS) this.incomplete(`Only the first ${MAX_TESTS} tests were recorded`);
    if (new Set(records.map(record => record.id)).size !== records.length) {
      process.exitCode = 1;
      console.error('Playwright Insights: duplicate stable test identity');
      return;
    }
    const manifest = {
      schemaVersion: '1.0.0',
      producer: { name: 'playwright-jenkins-insights', version: '0.1.0' },
      run: {
        id: randomUUID(),
        startedAt: this.startedAt.toISOString(),
        durationMs: Math.max(0, Date.now() - this.startedAt.getTime()),
        complete: (result.status === 'passed' || result.status === 'failed') && !this.partial,
        ...((this.options.comparisonKey || process.env.PLAYWRIGHT_INSIGHTS_COMPARISON_KEY) && {
          comparisonKey: clipped(this.options.comparisonKey || process.env.PLAYWRIGHT_INSIGHTS_COMPARISON_KEY || '', 256)
        }),
        ...((this.options.metadata || process.env.PLAYWRIGHT_INSIGHTS_BRANCH || process.env.PLAYWRIGHT_INSIGHTS_COMMIT || process.env.PLAYWRIGHT_INSIGHTS_SELECTION) && {
          metadata: {
            ...(this.options.metadata?.branch || process.env.PLAYWRIGHT_INSIGHTS_BRANCH ? { branch: this.preview(this.options.metadata?.branch || process.env.PLAYWRIGHT_INSIGHTS_BRANCH || '', 256) } : {}),
            ...(this.options.metadata?.commit || process.env.PLAYWRIGHT_INSIGHTS_COMMIT ? { commit: this.preview(this.options.metadata?.commit || process.env.PLAYWRIGHT_INSIGHTS_COMMIT || '', 128) } : {}),
            ...(this.options.metadata?.selection || process.env.PLAYWRIGHT_INSIGHTS_SELECTION ? { selection: this.preview(this.options.metadata?.selection || process.env.PLAYWRIGHT_INSIGHTS_SELECTION || '', 512) } : {})
          }
        })
      },
      projects,
      tests: records,
      warnings: this.warnings
    };
    const output = resolve(this.options.outputFile || process.env.PLAYWRIGHT_INSIGHTS_OUTPUT_FILE || 'playwright-insights/manifest.json');
    const temp = `${output}.${randomUUID()}.tmp`;
    try {
      const data = JSON.stringify(manifest);
      if (Buffer.byteLength(data) > MAX_BYTES) throw new Error('manifest exceeds 25 MiB');
      mkdirSync(dirname(output), { recursive: true });
      writeFileSync(temp, data);
      renameSync(temp, output);
    } catch (error) {
      try { rmSync(temp, { force: true }); } catch { /* Preserve the original write error. */ }
      process.exitCode = 1;
      console.error(`Playwright Insights: failed to write manifest: ${String(error)}`);
    }
  }

  private record(test: TestCase) {
    const projectId = clipped(test.parent.project()?.name || 'default', 128);
    const file = this.relativePath(test.location.file);
    if (!file) this.incomplete(`Test source path was omitted: ${this.preview(test.title, 100)}`);
    if (test.outcome() === 'skipped' && test.expectedStatus !== 'skipped') this.incomplete(`Uncollected test: ${this.preview(test.title, 100)}`);
    // titlePath starts with project and file suites; identity uses only describe/test titles.
    const titlePath = test.titlePath().slice(3);
    if (titlePath.length > 32 || titlePath.some(title => title.length > 512)) this.incomplete(`Title path truncated: ${this.preview(test.title, 100)}`);
    const stableTitle = (titlePath.length ? titlePath : [test.title]).slice(0, 32).map(title => this.preview(title, 512));
    const id = createHash('sha256').update(JSON.stringify([projectId, file, stableTitle, test.repeatEachIndex])).digest('hex');
    if (test.results.length > 16) this.incomplete(`Retry limit reached for ${id}`);
    for (const attempt of test.results) {
      if (attempt.errors.length > 8 || attempt.attachments.length > 16) this.incomplete(`Attempt details truncated for ${id}`);
    }
    return {
      id, projectId, file: file || 'unknown', line: Math.max(1, test.location.line),
      titlePath: stableTitle, repeatEachIndex: test.repeatEachIndex,
      expectedStatus: test.expectedStatus, outcome: test.outcome(),
      attempts: test.results.slice(0, 16).map((attempt, index) => {
        return {
          retry: index, status: attempt.status, durationMs: Math.max(0, Math.round(attempt.duration)),
          errors: attempt.errors.slice(0, 8).map(error => ({
            message: this.preview(error.message || error.value || 'Unknown error', 8192),
            ...(error.stack && { stack: this.preview(error.stack, 32768) })
          })),
          attachments: attempt.attachments.slice(0, 16).flatMap(attachment => {
            if (!attachment.path) {
              this.warn(`In-memory attachment was omitted for ${id}`);
              return [];
            }
            const path = this.relativePath(attachment.path);
            if (!path || !attachment.name || !attachment.contentType) {
              this.warn(`Attachment path was omitted for ${id}`);
              return [];
            }
            return [{ name: clipped(attachment.name, 256), contentType: clipped(attachment.contentType, 128), path }];
          })
        };
      })
    };
  }
}
