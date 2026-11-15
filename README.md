# Playwright Insights for Jenkins

Playwright-aware test results in Jenkins. A Playwright reporter writes a versioned manifest, and a Jenkins Pipeline plugin imports it to show standard test statuses, run and test durations, recorded assertion steps, inline screenshots and video, and build history.

The repository contains the [manifest contract](schema/README.md), a Playwright reporter, and a Jenkins Pipeline plugin. See [the product and engineering spec](docs/SPEC.md) for the MVP scope and [the quickstart](docs/QUICKSTART.md) for a local integration.

For public releases, see the [publishing checklist](docs/PUBLISHING.md). The plugin has not been released to the Jenkins Update Center or the reporter to npm.

Run the contract and reporter checks with `npm ci && npm run typecheck && npm test`. Build the reporter package with `npm run build:reporter`.
