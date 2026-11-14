import { test, expect } from '@playwright/test';

test('passes', () => { expect(1).toBe(1); });

test('fails', async ({}, testInfo) => {
  await testInfo.attach('screenshot', { body: Buffer.from('fixture'), contentType: 'image/png' });
  expect(1).toBe(2);
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
