#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

cd "$repo_root"
npm ci
npm run typecheck
npm test
npm run build:reporter

# The fixture intentionally has failing and unexpectedly passing tests.
if PLAYWRIGHT_INSIGHTS_OUTPUT_FILE="$tmp_dir/manifest.json" ./node_modules/.bin/playwright test --config reporter/fixtures/playwright.config.ts; then
  echo "Expected the Playwright fixture run to fail" >&2
  exit 1
fi

test -s "$tmp_dir/manifest.json"
if PLAYWRIGHT_JSON_OUTPUT_FILE="$tmp_dir/results.json" PLAYWRIGHT_BLOB_OUTPUT_FILE="$tmp_dir/results.zip" ./node_modules/.bin/playwright test --config reporter/fixtures/playwright.config.ts --reporter=json,blob; then
  echo "Expected the native Playwright JSON fixture run to fail" >&2
  exit 1
fi
test -s "$tmp_dir/results.json"
test -s "$tmp_dir/results.zip"
cd "$repo_root"
mvn -Dplaywright.insights.realManifest="$tmp_dir/manifest.json" -Dplaywright.insights.realJson="$tmp_dir/results.json" -Dplaywright.insights.realBlob="$tmp_dir/results.zip" verify
