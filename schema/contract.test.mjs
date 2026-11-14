import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import Ajv2020 from 'ajv/dist/2020.js';

const schema = JSON.parse(readFileSync(new URL('./manifest-v1.schema.json', import.meta.url)));
const ajv = new Ajv2020({ allErrors: true });
const validate = ajv.compile(schema);
const fixture = name => JSON.parse(readFileSync(new URL(`./fixtures/${name}.json`, import.meta.url)));

function contractErrors(manifest) {
  const errors = [];
  if (!validate(manifest)) return validate.errors.map(error => `${error.instancePath} ${error.message}`);

  const projects = new Set(manifest.projects.map(project => project.id));
  if (projects.size !== manifest.projects.length) errors.push('duplicate project id');
  const tests = new Set();
  for (const result of manifest.tests) {
    if (tests.has(result.id)) errors.push(`duplicate test id: ${result.id}`);
    tests.add(result.id);
    if (!projects.has(result.projectId)) errors.push(`unknown project: ${result.projectId}`);
    result.attempts.forEach((attempt, index) => {
      if (attempt.retry !== index) errors.push(`nonsequential retry: ${result.id}`);
    });
  }
  return errors;
}

for (const name of ['outcomes', 'empty', 'incomplete']) {
  test(`${name} fixture satisfies manifest v1`, () => {
    assert.deepEqual(contractErrors(fixture(name)), []);
  });
}

test('outcomes fixture counts logical tests once despite retry', () => {
  const counts = Object.groupBy(fixture('outcomes').tests, result => result.outcome);
  assert.deepEqual(Object.fromEntries(Object.entries(counts).map(([key, values]) => [key, values.length])), {
    expected: 2, unexpected: 2, flaky: 1, skipped: 1
  });
});

for (const [name, mutate] of [
  ['unsupported version', manifest => { manifest.schemaVersion = '2.0.0'; }],
  ['unknown field', manifest => { manifest.tests[0].secret = 'unexpected'; }],
  ['path traversal', manifest => { manifest.tests[1].attempts[0].attachments[0].path = '../secret'; }],
  ['absolute path', manifest => { manifest.tests[1].attempts[0].attachments[0].path = '/etc/passwd'; }],
  ['oversized error', manifest => { manifest.tests[1].attempts[0].errors[0].message = 'x'.repeat(8193); }],
  ['duplicate test', manifest => { manifest.tests[1].id = manifest.tests[0].id; }],
  ['unknown project', manifest => { manifest.tests[0].projectId = 'missing'; }],
  ['out of order retry', manifest => { manifest.tests[2].attempts[1].retry = 3; }]
]) {
  test(`rejects ${name}`, () => {
    const manifest = fixture('outcomes');
    mutate(manifest);
    assert.notDeepEqual(contractErrors(manifest), []);
  });
}
