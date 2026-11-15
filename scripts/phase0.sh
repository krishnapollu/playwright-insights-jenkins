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
cd "$repo_root"
mvn -Dplaywright.insights.realManifest="$tmp_dir/manifest.json" verify
