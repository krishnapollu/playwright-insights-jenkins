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
    public String getStartedAt() { return manifest().run().getString("startedAt"); }
    public long getDurationMs() { return manifest().run().getLong("durationMs"); }
    public JSONArray getWarnings() { return manifest().warnings(); }

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

    public List<ProjectSummary> getProjectSummaries() {
        Manifest manifest = manifest();
        Map<String, ProjectSummary> summaries = new LinkedHashMap<>();
        for (Object value : manifest.projects()) {
            JSONObject project = (JSONObject) value;
            summaries.put(project.getString("id"), new ProjectSummary(project.getString("name")));
        }
        for (Object value : manifest.tests()) {
            JSONObject test = (JSONObject) value;
            summaries.get(test.getString("projectId")).add(test.getString("outcome"));
        }
        return new ArrayList<>(summaries.values());
    }

    private String parameter(String name) {
        var request = Stapler.getCurrentRequest2();
        String value = request == null ? null : request.getParameter(name);
        return value == null ? "" : value;
    }

    public String getOutcomeFilter() { return parameter("outcome"); }
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
        String project = getProjectFilter();
        String query = getQuery().toLowerCase(java.util.Locale.ROOT);
        List<TestRow> matched = new ArrayList<>();
        for (Object value : manifest().tests()) {
            TestRow row = new TestRow((JSONObject) value);
            if (!outcome.isEmpty() && !outcome.equals(row.getOutcome())) continue;
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
        return "?page=" + (getPage() + 1) + "&outcome=" + Util.rawEncode(getOutcomeFilter())
                + "&project=" + Util.rawEncode(getProjectFilter()) + "&q=" + Util.rawEncode(getQuery());
    }

    public TestRow getSelectedTest() {
        String id = parameter("test");
        if (id.isEmpty() || id.length() > 128) return null;
        for (Object value : manifest().tests()) {
            JSONObject test = (JSONObject) value;
            if (id.equals(test.getString("id"))) return new TestRow(test);
        }
        return null;
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
            String outcome = "Not observed";
            for (Object value : candidate.tests()) {
                JSONObject test = (JSONObject) value;
                if (selected.getId().equals(test.getString("id"))) { outcome = test.getString("outcome"); break; }
            }
            history.add(new HistoryRow(prior.getDisplayName(), prior.getUrl(), candidate.run().getString("startedAt"), outcome));
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
        if (now.equals("unexpected") && before.equals("unexpected")) return "Continuing failure";
        if (now.equals("unexpected") && (before.equals("expected") || before.equals("flaky"))) return "New failure";
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

    public static class ProjectSummary {
        private final String name;
        private final Map<String, Integer> counts = new LinkedHashMap<>();
        ProjectSummary(String name) { this.name = name; }
        void add(String outcome) { counts.merge(outcome, 1, Integer::sum); }
        public String getName() { return name; }
        public int getExpected() { return counts.getOrDefault("expected", 0); }
        public int getUnexpected() { return counts.getOrDefault("unexpected", 0); }
        public int getFlaky() { return counts.getOrDefault("flaky", 0); }
        public int getSkipped() { return counts.getOrDefault("skipped", 0); }
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
        public JSONArray getAttempts() { return data.getJSONArray("attempts"); }
        public long getDurationMs() {
            long duration = 0;
            for (Object value : getAttempts()) duration += ((JSONObject) value).getLong("durationMs");
            return duration;
        }
    }

    public static class HistoryRow {
        private final String build;
        private final String url;
        private final String startedAt;
        private final String outcome;
        HistoryRow(String build, String url, String startedAt, String outcome) {
            this.build = build;
            this.url = url;
            this.startedAt = startedAt;
            this.outcome = outcome;
        }
        public String getBuild() { return build; }
        public String getUrl() { return url; }
        public String getStartedAt() { return startedAt; }
        public String getOutcome() { return outcome; }
    }
}
