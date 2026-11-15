package io.jenkins.plugins.playwrightinsights;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Result;
import hudson.model.Item;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import jenkins.model.Jenkins;

@WithJenkins
class PlaywrightInsightsStepTest {
    @Test void pipelineImportsAndShowsLogicalResults(JenkinsRule j) throws Exception {
        String fixture = Files.readString(Path.of("..", "schema", "fixtures", "outcomes.json"))
                .replace("\"name\": \"chromium\"", "\"name\": \"Chrome browser\"");
        String base64 = Base64.getEncoder().encodeToString(fixture.getBytes(StandardCharsets.UTF_8));
        String png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/l9sAAAAASUVORK5CYII=";
        WorkflowJob job = j.createProject(WorkflowJob.class, "playwright");
        job.setDefinition(new CpsFlowDefinition("node { writeFile file: 'manifest.json', text: '" + base64
                + "', encoding: 'Base64'; playwrightInsights results: 'manifest.json'; "
                + "sh 'mkdir -p test-results/fails'; writeFile file: 'test-results/fails/screenshot.png', text: '" + png + "', encoding: 'Base64'; "
                + "archiveArtifacts artifacts: 'test-results/**/*' }", true));
        WorkflowRun run = j.buildAndAssertSuccess(job);
        assertEquals(Result.SUCCESS, run.getResult());
        InsightsAction action = run.getAction(InsightsAction.class);
        assertNotNull(action);
        assertEquals(6, action.getTestCount());
        assertEquals(2, action.getCounts().get("unexpected"));
        String page = j.createWebClient().goTo(run.getUrl() + "playwright-insights/").getWebResponse().getContentAsString();
        assertTrue(page.contains("Playwright Insights"));
        assertTrue(page.contains("RUN OVERVIEW"));
        assertTrue(page.contains("expected failure"));
        assertTrue(page.contains("pi-test-item is-selected"));
        assertTrue(page.contains("/plugin/playwright-insights/css/insights.css"));
        String filtered = j.createWebClient().goTo(run.getUrl() + "playwright-insights/?project=chromium&outcome=unexpected")
                .getWebResponse().getContentAsString();
        assertTrue(filtered.contains("Chrome browser"));
        assertTrue(filtered.contains("fails"));
        assertFalse(filtered.contains("passes on retry"));
        var detailPage = j.createWebClient().goTo(run.getUrl() + "playwright-insights/?test=fail-1");
        String detail = detailPage.getWebResponse().getContentAsString();
        assertTrue(detail.contains("screenshot.png"));
        assertTrue(detail.contains("Assertion failed"));
        assertTrue(detail.contains("<img"));
        var evidence = detailPage.getAnchors().stream().filter(anchor -> anchor.getHrefAttribute().contains("screenshot.png")).findFirst().orElseThrow();
        assertEquals("image/png", evidence.click().getWebResponse().getContentType());
        assertEquals("file", action.mediaKind(net.sf.json.JSONObject.fromObject("{\"name\":\"screenshot\",\"contentType\":\"image/png\",\"path\":\"test-results/fails/unsafe.html\"}")));
        assertEquals("video", action.mediaKind(net.sf.json.JSONObject.fromObject("{\"name\":\"video\",\"contentType\":\"video/webm\",\"path\":\"test-results/fails/video.webm\"}")));
        assertEquals("trace", action.mediaKind(net.sf.json.JSONObject.fromObject("{\"name\":\"trace\",\"contentType\":\"application/zip\",\"path\":\"test-results/fails/trace.zip\"}")));
        assertTrue(j.createWebClient().goTo(run.getUrl() + "playwright-insights/?test=missing")
                .getWebResponse().getContentAsString().contains("Choose a test to inspect"));
        WorkflowRun second = j.buildAndAssertSuccess(job);
        assertEquals(0, second.getAction(InsightsAction.class).getComparisonCounts().get("unclassified"));
        String history = j.createWebClient().goTo(second.getUrl() + "playwright-insights/?test=fail-1").getWebResponse().getContentAsString();
        assertTrue(history.contains("Continuing failure"));
        String trends = j.createWebClient().goTo(job.getUrl() + "playwright-trends/").getWebResponse().getContentAsString();
        assertTrue(trends.contains("Run results"));
        assertEquals("Playwright Insights", job.getAction(TrendsAction.class).getDisplayName());
        assertTrue(trends.contains("#1"));
    }

    @Test void invalidPathWarnsWithoutChangingResult(JenkinsRule j) throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class, "invalid");
        job.setDefinition(new CpsFlowDefinition("node { playwrightInsights results: '../outside.json' }", true));
        WorkflowRun run = j.buildAndAssertSuccess(job);
        assertNull(run.getAction(InsightsAction.class));
        j.assertLogContains("Playwright Insights import warning", run);
    }

    @Test void strictImportFailsWithoutEchoingManifestContent(JenkinsRule j) throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class, "strict");
        job.setDefinition(new CpsFlowDefinition("node { writeFile file: 'manifest.json', text: '{secret-value}'; "
                + "playwrightInsights results: 'manifest.json', failOnImportError: true }", true));
        WorkflowRun run = j.buildAndAssertStatus(Result.FAILURE, job);
        assertNull(run.getAction(InsightsAction.class));
        assertFalse(run.getLog().contains("secret-value"));
    }

    @Test void untrustedTitlesAreEscaped(JenkinsRule j) throws Exception {
        String fixture = Files.readString(Path.of("..", "schema", "fixtures", "outcomes.json"))
                .replace("\"passes\"", "\"<script>alert(1)</script>\"");
        String base64 = Base64.getEncoder().encodeToString(fixture.getBytes(StandardCharsets.UTF_8));
        WorkflowJob job = j.createProject(WorkflowJob.class, "untrusted");
        job.setDefinition(new CpsFlowDefinition("node { writeFile file: 'manifest.json', text: '" + base64
                + "', encoding: 'Base64'; playwrightInsights results: 'manifest.json' }", true));
        WorkflowRun run = j.buildAndAssertSuccess(job);
        String page = j.createWebClient().goTo(run.getUrl() + "playwright-insights/").getWebResponse().getContentAsString();
        assertTrue(page.contains("alert(1)"));
        assertFalse(page.contains("<script>alert(1)</script>"));
    }

    @Test void buildPageRequiresJobRead(JenkinsRule j) throws Exception {
        String fixture = Files.readString(Path.of("..", "schema", "fixtures", "empty.json"));
        String base64 = Base64.getEncoder().encodeToString(fixture.getBytes(StandardCharsets.UTF_8));
        WorkflowJob job = j.createProject(WorkflowJob.class, "private-results");
        job.setDefinition(new CpsFlowDefinition("node { writeFile file: 'manifest.json', text: '" + base64
                + "', encoding: 'Base64'; playwrightInsights results: 'manifest.json' }", true));
        WorkflowRun run = j.buildAndAssertSuccess(job);
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ).everywhere().to("reader", "outsider")
                .grant(Item.READ).onItems(job).to("reader"));
        j.createWebClient().withBasicCredentials("outsider")
                .assertFails(run.getUrl() + "playwright-insights/", HttpURLConnection.HTTP_NOT_FOUND);
        String page = j.createWebClient().withBasicCredentials("reader")
                .goTo(run.getUrl() + "playwright-insights/").getWebResponse().getContentAsString();
        assertTrue(page.contains("No tests were recorded"));
    }

    @Test void realReporterManifestImports(JenkinsRule j) throws Exception {
        String path = System.getProperty("playwright.insights.realManifest");
        Assumptions.assumeTrue(path != null, "run scripts/phase0.sh for this end-to-end check");
        String manifest = Files.readString(Path.of(path));
        String base64 = Base64.getEncoder().encodeToString(manifest.getBytes(StandardCharsets.UTF_8));
        WorkflowJob job = j.createProject(WorkflowJob.class, "real-reporter");
        job.setDefinition(new CpsFlowDefinition("node { writeFile file: 'manifest.json', text: '" + base64
                + "', encoding: 'Base64'; playwrightInsights results: 'manifest.json' }", true));
        WorkflowRun run = j.buildAndAssertSuccess(job);
        InsightsAction action = run.getAction(InsightsAction.class);
        assertNotNull(action);
        assertEquals(6, action.getTestCount());
        assertEquals(2, action.getCounts().get("unexpected"));
        assertEquals(1, action.getCounts().get("flaky"));
        assertTrue(action.manifest().tests().stream().map(value -> (net.sf.json.JSONObject) value)
                .anyMatch(test -> test.optJSONArray("tags") != null && test.getJSONArray("tags").contains("@critical")
                        && test.getJSONArray("attempts").getJSONObject(0).getJSONArray("steps").size() > 0));
    }
}
