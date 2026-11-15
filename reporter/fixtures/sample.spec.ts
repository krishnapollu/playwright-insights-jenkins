import { test, expect } from '@playwright/test';

test('passes', () => { expect(1).toBe(1); });

test('fails', { tag: '@critical', annotation: { type: 'owner', description: 'QA' } }, async ({}, testInfo) => {
  await testInfo.attach('screenshot', { body: Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/l9sAAAAASUVORK5CYII=', 'base64'), contentType: 'image/png' });
  console.log('Calculation diagnostic');
  await test.step('Compare values', () => { expect(1).toBe(2); });
});

test('flaky', ({}, testInfo) => { expect(testInfo.retry).toBe(1); });

test('expected failure', () => {
  test.fail();
  expect(1).toBe(2);
});

test('unexpected pass', () => {
  test.fail();
  expect(1).toBe(1);
});

test('skipped', () => { test.skip(); });
