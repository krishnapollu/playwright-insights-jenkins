import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import Ajv2020 from 'ajv/dist/2020.js';

test('real Playwright run emits a schema-valid manifest with logical outcomes', () => {
  const dir = mkdtempSync(join(tmpdir(), 'playwright-insights-'));
  try {
    const output = join(dir, 'manifest.json');
    const run = spawnSync(process.execPath, [
      './node_modules/@playwright/test/cli.js', 'test', '--config', 'reporter/fixtures/playwright.config.ts'
    ], {
      cwd: process.cwd(), encoding: 'utf8', timeout: 60_000,
      env: { ...process.env, PLAYWRIGHT_INSIGHTS_OUTPUT_FILE: output, FORCE_COLOR: '0' }
    });
    assert.equal(run.status, 1, run.stdout + run.stderr);
    const manifest = JSON.parse(readFileSync(output, 'utf8'));
    const schema = JSON.parse(readFileSync(new URL('../schema/manifest-v1.schema.json', import.meta.url)));
    const validate = new Ajv2020().compile(schema);
    assert.equal(validate(manifest), true, JSON.stringify(validate.errors));
    assert.equal(manifest.run.complete, true);
    assert.equal(manifest.schemaVersion, '1.1.0');
    assert.equal(manifest.tests.length, 6);
    assert.deepEqual(Object.fromEntries(manifest.tests.map(item => [item.titlePath.at(-1), item.outcome])), {
      passes: 'expected', fails: 'unexpected', flaky: 'flaky',
      'expected failure': 'expected', 'unexpected pass': 'unexpected', skipped: 'skipped'
    });
    assert.deepEqual(manifest.tests.find(item => item.titlePath.at(-1) === 'flaky').attempts.map(item => item.retry), [0, 1]);
    assert.ok(manifest.tests.find(item => item.titlePath.at(-1) === 'fails').attempts[0].attachments[0].path.startsWith('test-results/'));
    const failure = manifest.tests.find(item => item.titlePath.at(-1) === 'fails');
    assert.ok(failure.tags.includes('@critical'));
    assert.ok(failure.annotations.some(item => item.type === 'owner' && item.description === 'QA'));
    assert.ok(failure.attempts[0].steps.some(item => item.title === 'Compare values'));
    assert.match(failure.attempts[0].stdout, /Calculation diagnostic/);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
