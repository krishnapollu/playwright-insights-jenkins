package io.jenkins.plugins.playwrightinsights;

import hudson.model.Item;
import hudson.model.Run;
import hudson.Util;
import jenkins.model.RunAction2;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.Stapler;

public class InsightsAction implements RunAction2, Serializable {
    private static final long serialVersionUID = 1L;
    private static final String SNAPSHOT = "playwright-insights.json";
    private transient Run<?, ?> run;
    private final int testCount;
    private final boolean complete;

    private InsightsAction(int testCount, boolean complete) {
        this.testCount = testCount;
        this.complete = complete;
    }

    static void save(Run<?, ?> run, byte[] bytes, Manifest manifest) throws IOException {
        Path target = new File(run.getRootDir(), SNAPSHOT).toPath();
        Path temporary = Files.createTempFile(run.getRootDir().toPath(), "playwright-insights-", ".tmp");
        try {
            Files.write(temporary, bytes);
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            run.addAction(new InsightsAction(manifest.tests().size(), manifest.complete()));
            run.save();
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override public void onAttached(Run<?, ?> run) { this.run = run; }
    @Override public void onLoad(Run<?, ?> run) { this.run = run; }
    @Override public String getIconFileName() { return "graph.png"; }
    @Override public String getDisplayName() { return "Playwright Insights"; }
    @Override public String getUrlName() { return "playwright-insights"; }

    public int getTestCount() { return testCount; }
    public boolean isComplete() { return complete; }
    public String getBuildName() { return run.getDisplayName(); }
    public String getDashboardUrl() { return run.getParent().getUrl() + "playwright-trends/"; }
    public String getStartedAt() { return manifest().run().getString("startedAt"); }
    public long getDurationMs() { return manifest().run().getLong("durationMs"); }
    public String getDurationDisplay() { return formatDuration(getDurationMs()); }
    public JSONArray getWarnings() { return manifest().warnings(); }
    static String formatDuration(long milliseconds) {
        if (milliseconds < 1000) return milliseconds + " ms";
        if (milliseconds < 60_000) return String.format(Locale.ROOT, "%.1f s", milliseconds / 1000.0);
        return String.format(Locale.ROOT, "%d min %02d s", milliseconds / 60_000, milliseconds / 1000 % 60);
    }

    Manifest manifest() {
        run.getParent().checkPermission(Item.READ);
        try {
            return Manifest.parse(Files.readAllBytes(new File(run.getRootDir(), SNAPSHOT).toPath()));
        } catch (IOException error) {
            throw new IllegalStateException("Playwright Insights snapshot is unavailable", error);
        }
    }

    public Map<String, Integer> getCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String outcome : List.of("expected", "unexpected", "flaky", "skipped")) counts.put(outcome, 0);
        for (Object value : manifest().tests()) {
            JSONObject test = (JSONObject) value;
            counts.merge(test.getString("outcome"), 1, Integer::sum);
        }
        return counts;
    }

    public Map<String, Integer> getStatusCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String status : List.of("passed", "failed", "timedOut", "skipped", "interrupted")) counts.put(status, 0);
        for (Object value : manifest().tests()) counts.merge(new TestRow((JSONObject) value).getStatus(), 1, Integer::sum);
        return counts;
    }

    public int getRecordedAssertions() {
        int count = 0;
        for (Object value : manifest().tests()) count += new TestRow((JSONObject) value).getAssertionCount();
        return count;
    }

    public String getStatusChartStyle() {
        Map<String, Integer> counts = getStatusCounts();
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        if (total == 0) return "background:#dce3ec";
        String[] statuses = {"passed", "failed", "timedOut", "skipped", "interrupted"};
        String[] colors = {"#32946a", "#bd4257", "#c67a39", "#8796a5", "#8063a7"};
        StringBuilder chart = new StringBuilder("background:conic-gradient(");
        double start = 0;
        for (int i = 0; i < statuses.length; i++) {
            double end = i == statuses.length - 1 ? 100 : start + counts.get(statuses[i]) * 100.0 / total;
            if (i > 0) chart.append(',');
            chart.append(colors[i]).append(' ').append(String.format(Locale.ROOT, "%.2f%% %.2f%%", start, end));
            start = end;
        }
        return chart.append(')').toString();
    }

    public List<ProjectSummary> getProjectSummaries() {
        Manifest manifest = manifest();
        Map<String, ProjectSummary> summaries = new LinkedHashMap<>();
        for (Object value : manifest.projects()) {
            JSONObject project = (JSONObject) value;
            summaries.put(project.getString("id"), new ProjectSummary(project.getString("id"), project.getString("name")));
        }
        for (Object value : manifest.tests()) {
            JSONObject test = (JSONObject) value;
            summaries.get(test.getString("projectId")).add(new TestRow(test).getStatus());
        }
        return new ArrayList<>(summaries.values());
    }

    private String parameter(String name) {
        var request = Stapler.getCurrentRequest2();
        String value = request == null ? null : request.getParameter(name);
        return value == null ? "" : value;
    }

    public String getOutcomeFilter() { return parameter("outcome"); }
    public String getStatusFilter() { return parameter("status"); }
    public String getProjectFilter() { return parameter("project"); }
    public String getQuery() { return parameter("q"); }
    public int getPage() {
        try { return Math.max(1, Math.min(200, Integer.parseInt(parameter("page")))); }
        catch (NumberFormatException ignored) { return 1; }
    }

    public List<TestRow> getTests() {
        List<TestRow> matched = filteredTests();
        int start = Math.min(matched.size(), (getPage() - 1) * 100);
        return matched.subList(start, Math.min(matched.size(), start + 100));
    }

    private List<TestRow> filteredTests() {
        String outcome = getOutcomeFilter();
        String status = getStatusFilter();
        String project = getProjectFilter();
        String query = getQuery().toLowerCase(java.util.Locale.ROOT);
        List<TestRow> matched = new ArrayList<>();
        for (Object value : manifest().tests()) {
            TestRow row = new TestRow((JSONObject) value);
            if (!outcome.isEmpty() && !outcome.equals(row.getOutcome())) continue;
            if (!status.isEmpty() && !status.equals(row.getStatus())) continue;
            if (!project.isEmpty() && !project.equals(row.getProjectId())) continue;
            if (!query.isEmpty() && !(row.getTitle().toLowerCase(java.util.Locale.ROOT).contains(query)
                    || row.getFile().toLowerCase(java.util.Locale.ROOT).contains(query))) continue;
            matched.add(row);
        }
        return matched;
    }

    public boolean isMoreTests() {
        return filteredTests().size() > getPage() * 100;
    }

    public String getNextPageUrl() {
        return "?page=" + (getPage() + 1) + "&status=" + Util.rawEncode(getStatusFilter()) + "&outcome=" + Util.rawEncode(getOutcomeFilter())
                + "&project=" + Util.rawEncode(getProjectFilter()) + "&q=" + Util.rawEncode(getQuery());
    }

    public TestRow getSelectedTest() {
        String id = parameter("test");
        if (!id.isEmpty()) {
            if (id.length() > 128) return null;
            for (Object value : manifest().tests()) {
                JSONObject test = (JSONObject) value;
                if (id.equals(test.getString("id"))) return new TestRow(test);
            }
            return null;
        }
        List<TestRow> visible = getTests();
        for (TestRow test : visible) if (test.getOutcome().equals("unexpected")) return test;
        return visible.isEmpty() ? null : visible.get(0);
    }

    public String testUrl(TestRow test) {
        return "?status=" + Util.rawEncode(getStatusFilter()) + "&outcome=" + Util.rawEncode(getOutcomeFilter()) + "&project=" + Util.rawEncode(getProjectFilter())
                + "&q=" + Util.rawEncode(getQuery()) + "&page=" + getPage()
                + "&test=" + test.getLinkId() + "#test-detail";
    }

    public String getPreviousComparableBuild() {
        Run<?, ?> baseline = previousComparable(manifest());
        return baseline == null ? "" : baseline.getDisplayName();
    }

    public Map<String, Integer> getComparisonCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String name : List.of("newFailures", "continuingFailures", "recovered", "newlyObserved", "unclassified")) counts.put(name, 0);
        Manifest current = manifest();
        Run<?, ?> baseline = previousComparable(current);
        if (baseline == null) return counts;
        Map<String, String> before = new java.util.HashMap<>();
        for (Object value : baseline.getAction(InsightsAction.class).manifest().tests()) {
            JSONObject test = (JSONObject) value;
            before.put(test.getString("id"), test.getString("outcome"));
        }
        for (Object value : current.tests()) {
            JSONObject test = (JSONObject) value;
            String prior = before.get(test.getString("id"));
            String now = test.getString("outcome");
            if (prior == null) counts.merge("newlyObserved", 1, Integer::sum);
            else if (now.equals("unexpected") && prior.equals("unexpected")) counts.merge("continuingFailures", 1, Integer::sum);
            else if (now.equals("unexpected") && (prior.equals("expected") || prior.equals("flaky"))) counts.merge("newFailures", 1, Integer::sum);
            else if ((now.equals("expected") || now.equals("flaky")) && prior.equals("unexpected")) counts.merge("recovered", 1, Integer::sum);
            else if (!now.equals(prior) && (now.equals("skipped") || prior.equals("skipped"))) counts.merge("unclassified", 1, Integer::sum);
        }
        return counts;
    }

    private Run<?, ?> previousComparable(Manifest current) {
        if (current.comparisonKey().isEmpty()) return null;
        Run<?, ?> prior = run.getPreviousBuild();
        for (int examined = 0; prior != null && examined < 50; prior = prior.getPreviousBuild(), examined++) {
            InsightsAction action = prior.getAction(InsightsAction.class);
            if (action == null) continue;
            Manifest candidate;
            try { candidate = action.manifest(); } catch (RuntimeException ignored) { continue; }
            if (!comparable(current, candidate)) continue;
            return prior;
        }
        return null;
    }

    boolean comparable(Manifest current, Manifest candidate) {
        if (!current.complete() || !candidate.complete() || current.comparisonKey().isEmpty()) return false;
        if (!current.comparisonKey().equals(candidate.comparisonKey())) return false;
        if (!projectIds(current).equals(projectIds(candidate))) return false;
        String currentSelection = current.run().optJSONObject("metadata") == null ? "" : current.run().getJSONObject("metadata").optString("selection", "");
        String priorSelection = candidate.run().optJSONObject("metadata") == null ? "" : candidate.run().getJSONObject("metadata").optString("selection", "");
        return currentSelection.equals(priorSelection);
    }

    public List<HistoryRow> getHistory() {
        TestRow selected = getSelectedTest();
        if (selected == null) return List.of();
        Manifest current = manifest();
        List<HistoryRow> history = new ArrayList<>();
        Run<?, ?> prior = run.getPreviousBuild();
        for (int examined = 0; prior != null && examined < 50 && history.size() < 10; prior = prior.getPreviousBuild(), examined++) {
            InsightsAction action = prior.getAction(InsightsAction.class);
            if (action == null) continue;
            Manifest candidate;
            try { candidate = action.manifest(); } catch (RuntimeException ignored) { continue; }
            if (!comparable(current, candidate)) continue;
            String status = "notObserved";
            String statusLabel = "Not observed";
            String note = "";
            for (Object value : candidate.tests()) {
                JSONObject test = (JSONObject) value;
                if (selected.getId().equals(test.getString("id"))) {
                    TestRow row = new TestRow(test);
                    status = row.getStatus();
                    statusLabel = row.getStatusLabel();
                    note = row.getOutcomeNote();
                    break;
                }
            }
            history.add(new HistoryRow(prior.getDisplayName(), prior.getUrl(), candidate.run().getString("startedAt"), status, statusLabel, note));
        }
        return history;
    }

    private Set<String> projectIds(Manifest manifest) {
        Set<String> ids = new java.util.HashSet<>();
        for (Object value : manifest.projects()) ids.add(((JSONObject) value).getString("id"));
        return ids;
    }

    public String getClassification() {
        TestRow selected = getSelectedTest();
        if (selected == null) return "";
        Run<?, ?> baseline = previousComparable(manifest());
        if (baseline == null) return "Unclassified: no comparable complete build";
        Manifest prior = baseline.getAction(InsightsAction.class).manifest();
        JSONObject previous = null;
        for (Object value : prior.tests()) {
            JSONObject test = (JSONObject) value;
            if (test.getString("id").equals(selected.getId())) { previous = test; break; }
        }
        if (previous == null) return "Newly observed";
        String before = previous.getString("outcome");
        String now = selected.getOutcome();
        if (now.equals("unexpected") && before.equals("unexpected")) return "Continuing result needing review";
        if (now.equals("unexpected") && (before.equals("expected") || before.equals("flaky"))) return "New result needing review";
        if ((now.equals("expected") || now.equals("flaky")) && before.equals("unexpected")) return now.equals("flaky") ? "Recovered, flaky in this run" : "Recovered";
        if (!now.equals(before) && (now.equals("skipped") || before.equals("skipped"))) return "Unclassified: skipped in one run";
        return "No failure change";
    }

    public String artifactLink(String path) {
        if (path == null || !path.matches("^(?!\\.{1,2}(?:/|$))[A-Za-z0-9_@()+, .=-]+(/(?!\\.{1,2}(?:/|$))[A-Za-z0-9_@()+, .=-]+)*$")) return "";
        try {
            var root = run.getArtifactManager().root();
            var file = root;
            for (String segment : path.split("/")) {
                file = file.child(segment);
                if (file.readLink() != null) return "";
            }
            if (!file.isFile() || run.getArtifacts().stream().noneMatch(artifact -> artifact.relativePath.equals(path))) return "";
        } catch (IOException error) { return ""; }
        return run.getUrl() + "artifact/" + String.join("/", java.util.Arrays.stream(path.split("/")).map(Util::rawEncode).toList());
    }

    public String mediaKind(JSONObject attachment) {
        String path = attachment.getString("path").toLowerCase(Locale.ROOT);
        String type = attachment.getString("contentType").toLowerCase(Locale.ROOT);
        if ((type.equals("image/png") && path.endsWith(".png"))
                || (type.equals("image/jpeg") && (path.endsWith(".jpg") || path.endsWith(".jpeg")))
                || (type.equals("image/webp") && path.endsWith(".webp"))) return "image";
        if ((type.equals("video/webm") && path.endsWith(".webm"))
                || (type.equals("video/mp4") && path.endsWith(".mp4"))) return "video";
        if (type.equals("application/zip") && path.endsWith(".zip")
                && attachment.getString("name").toLowerCase(Locale.ROOT).contains("trace")) return "trace";
        return "file";
    }

    public static class ProjectSummary {
        private final String id;
        private final String name;
        private final Map<String, Integer> counts = new LinkedHashMap<>();
        ProjectSummary(String id, String name) { this.id = id; this.name = name; }
        void add(String status) { counts.merge(status, 1, Integer::sum); }
        public String getId() { return id; }
        public String getName() { return name; }
        public int getPassed() { return counts.getOrDefault("passed", 0); }
        public int getFailed() { return counts.getOrDefault("failed", 0); }
        public int getTimedOut() { return counts.getOrDefault("timedOut", 0); }
        public int getSkipped() { return counts.getOrDefault("skipped", 0); }
        public int getInterrupted() { return counts.getOrDefault("interrupted", 0); }
    }

    public static class TestRow {
        private final JSONObject data;
        TestRow(JSONObject data) { this.data = data; }
        public String getId() { return data.getString("id"); }
        public String getLinkId() { return Util.rawEncode(getId()); }
        public String getTitle() {
            List<String> parts = new ArrayList<>();
            for (Object value : data.getJSONArray("titlePath")) parts.add((String) value);
            return String.join(" / ", parts);
        }
        public String getFile() { return data.getString("file"); }
        public int getLine() { return data.getInt("line"); }
        public String getProjectId() { return data.getString("projectId"); }
        public String getExpectedStatus() { return data.getString("expectedStatus"); }
        public String getOutcome() { return data.getString("outcome"); }
        public String getStatus() {
            JSONArray attempts = data.getJSONArray("attempts");
            if (!attempts.isEmpty()) return attempts.getJSONObject(attempts.size() - 1).getString("status");
            return getOutcome().equals("skipped") ? "skipped" : "interrupted";
        }
        public String getStatusLabel() {
            return switch (getStatus()) {
                case "timedOut" -> "Timed out";
                case "passed" -> "Passed";
                case "failed" -> "Failed";
                case "skipped" -> "Skipped";
                default -> "Interrupted";
            };
        }
        public String getOutcomeNote() {
            if (getOutcome().equals("flaky")) return "Flaky after retry";
            if (getExpectedStatus().equals("failed") && getOutcome().equals("expected")) return "Expected failure";
            if (getExpectedStatus().equals("failed") && getOutcome().equals("unexpected")) return "Unexpected pass";
            return "";
        }
        public JSONArray getTags() { return data.optJSONArray("tags") == null ? new JSONArray() : data.getJSONArray("tags"); }
        public JSONArray getAnnotations() { return data.optJSONArray("annotations") == null ? new JSONArray() : data.getJSONArray("annotations"); }
        public List<AttemptRow> getAttempts() {
            List<AttemptRow> attempts = new ArrayList<>();
            for (Object value : data.getJSONArray("attempts")) attempts.add(new AttemptRow((JSONObject) value));
            return attempts;
        }
        public long getDurationMs() {
            long duration = 0;
            for (AttemptRow attempt : getAttempts()) duration += attempt.getDurationMs();
            return duration;
        }
        public String getDurationDisplay() { return formatDuration(getDurationMs()); }
        public int getAssertionCount() {
            int count = 0;
            for (AttemptRow attempt : getAttempts()) for (Object value : attempt.getSteps())
                if (((JSONObject) value).getString("category").equals("expect")) count++;
            return count;
        }
        public int getFailedAssertionCount() {
            int count = 0;
            for (AttemptRow attempt : getAttempts()) for (Object value : attempt.getSteps()) {
                JSONObject step = (JSONObject) value;
                if (step.getString("category").equals("expect") && step.containsKey("error")) count++;
            }
            return count;
        }
    }

    public static class AttemptRow {
        private final JSONObject data;
        AttemptRow(JSONObject data) { this.data = data; }
        public int getRetry() { return data.getInt("retry"); }
        public String getStatus() { return data.getString("status"); }
        public long getDurationMs() { return data.getLong("durationMs"); }
        public String getDurationDisplay() { return formatDuration(getDurationMs()); }
        public int getWorkerIndex() { return data.containsKey("workerIndex") ? data.getInt("workerIndex") : -1; }
        public String getStdout() { return data.optString("stdout", ""); }
        public String getStderr() { return data.optString("stderr", ""); }
        public JSONArray getSteps() { return data.optJSONArray("steps") == null ? new JSONArray() : data.getJSONArray("steps"); }
        public JSONArray getErrors() { return data.getJSONArray("errors"); }
        public JSONArray getAttachments() { return data.getJSONArray("attachments"); }
    }

    public static class HistoryRow {
        private final String build;
        private final String url;
        private final String startedAt;
        private final String status;
        private final String statusLabel;
        private final String note;
        HistoryRow(String build, String url, String startedAt, String status, String statusLabel, String note) {
            this.build = build;
            this.url = url;
            this.startedAt = startedAt;
            this.status = status;
            this.statusLabel = statusLabel;
            this.note = note;
        }
        public String getBuild() { return build; }
        public String getUrl() { return url; }
        public String getStartedAt() { return startedAt; }
        public String getStatus() { return status; }
        public String getStatusLabel() { return statusLabel; }
        public String getNote() { return note; }
    }
}
