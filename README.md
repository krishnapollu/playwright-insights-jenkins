# Playwright Insights for Jenkins

Playwright-aware test results in Jenkins. A Playwright reporter writes a versioned manifest, and a Jenkins Pipeline plugin imports it to show run charts, failure details, inline screenshots and video, and build history.

The repository contains the [manifest contract](schema/README.md), a Playwright reporter, and a Jenkins Pipeline plugin. See [the product and engineering spec](docs/SPEC.md) for the MVP scope and [the quickstart](docs/QUICKSTART.md) for a local integration.

Run the contract and reporter checks with `npm ci && npm run typecheck && npm test`. Build the reporter package with `npm run build:reporter`.
