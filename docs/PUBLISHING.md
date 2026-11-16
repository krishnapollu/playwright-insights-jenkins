# Publishing Playwright Insights

The Jenkins plugin (`playwright-insights`) is the only artifact required for the default setup, which imports Playwright's built-in JSON report. The separate reporter (`playwright-jenkins-insights`) is optional for richer step detail and redaction. Publishing the source on GitHub does not distribute either artifact.

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

1. Create a [Jenkins community account](https://accounts.jenkins.io/) and log into Jira and Artifactory once. Record the Jenkins username for release permissions.
2. Submit a [plugin hosting request](https://www.jenkins.io/doc/developer/publishing/requesting-hosting/) in `jenkins-infra/repository-permissions-updater`. The request should identify:
   - Plugin name: **Playwright Insights**
   - Artifact ID: `playwright-insights`
   - Group ID: `io.jenkins.plugins`
   - Source: `https://github.com/krishnapollu/playwright-insights-jenkins`
   - GitHub maintainer: `krishnapollu`
   - Jenkins community username: **fill in after signup**
   - Distinction: Playwright-native attempts, steps, assertions, screenshots, video, and trace archives in Jenkins, with a versioned reporter manifest.
3. Address hosting team feedback. Jenkins will host the accepted source under `jenkinsci` and arrange release permissions. Update `gitHubRepo` and the project URL in `pom.xml`, plus documentation links, to the canonical repository.
4. Enable the [Jenkins GitHub release workflow](https://www.jenkins.io/doc/developer/publishing/releasing-cd/) in a reviewed pull request after hosting. Keep the release trigger manual until the first release is verified. A successful Jenkins CI build is required before release.
5. Release the plugin. Jenkins distributes approved releases through its Update Center and lists them on [plugins.jenkins.io](https://plugins.jenkins.io/).

The root `Jenkinsfile` prepares the plugin for ci.jenkins.io. The Maven project is at the repository root, with incremental versioning enabled. The repository and plugin POM declare the MIT license.

## Playwright reporter

The reporter is a separate, optional npm package. Its release can wait until there is demand for enhanced detail. After verifying account ownership and package contents, publish it from `reporter/` with `npm publish --access public`. The package name has not been reserved on npm yet.
