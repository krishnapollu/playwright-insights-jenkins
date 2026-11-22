# Publishing Playwright Insights

The Jenkins plugin (`playwright-insights`) is the only artifact required for the default setup, which imports Playwright's built-in JSON and blob reports. The separate reporter (`playwright-jenkins-insights`) is optional for richer API step detail and redaction. Publishing the source on GitHub does not distribute either artifact.

## Local release checks

From the repository root, with JDK 21, Maven, and Node 24:

```sh
npm ci
npm run typecheck
npm test
npm run build:reporter
npm pack ./reporter --dry-run
mvn verify
```

The Jenkins plugin archive is `target/playwright-insights.hpi`. The reporter package should contain its compiled JavaScript and types, README, license, and package metadata.

## Jenkins plugin hosting

1. Use the Jenkins community account `krishnapollu` and sign in to [Jira](https://issues.jenkins.io/) so the hosting checker can verify it. Artifactory sign-in is only needed for a manual release setup.
2. Run the [plugin hosting checker](https://github.com/jenkins-infra/plugin-hosting-checker) against the public repository and resolve all required findings.
3. Submit a [plugin hosting request](https://www.jenkins.io/doc/developer/publishing/requesting-hosting/) in `jenkins-infra/repository-permissions-updater`. The request should identify:
   - Plugin name: **Playwright Insights**
   - Artifact ID: `playwright-insights`
   - Group ID: `io.jenkins.plugins`
   - Source: `https://github.com/krishnapollu/playwright-insights-jenkins`
   - GitHub maintainer: `krishnapollu`
   - Jenkins community username: `krishnapollu`
   - Proposed hosted repository: `jenkinsci/playwright-insights-plugin`
   - Distinction: Playwright-native attempts, steps, assertions, screenshots, video, and trace archives in Jenkins; the separate reporter is optional.
4. Address hosting team feedback. Jenkins will host the accepted source under `jenkinsci` and arrange release permissions. The POM already points to the proposed canonical repository; update documentation links after the repository is created.
5. Run the [Jenkins GitHub release workflow](https://www.jenkins.io/doc/developer/publishing/releasing-cd/) after hosting and a successful Jenkins CI build. Its default `validate_only` input checks the release without publishing; turn it off when ready for the first release.
6. Release the plugin. Jenkins distributes approved releases through its Update Center and lists them on [plugins.jenkins.io](https://plugins.jenkins.io/).

The root `Jenkinsfile` prepares the plugin for ci.jenkins.io. The Maven project is at the repository root, with incremental versioning enabled. The repository and plugin POM declare the MIT license.

## Playwright reporter

The reporter is a separate, optional npm package. Its release can wait until there is demand for enhanced detail. After verifying account ownership and package contents, publish it from `reporter/` with `npm publish --access public`. The package name has not been reserved on npm yet.
