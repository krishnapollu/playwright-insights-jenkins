package io.jenkins.plugins.playwrightinsights;

import hudson.model.Action;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.Run;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.Stapler;

/** History is derived from retained builds, so Jenkins build deletion also deletes its data here. */
public class SpecsAction implements Action {
    private final Job<?, ?> job;

    SpecsAction(Job<?, ?> job) { this.job = job; }
    @Override public String getIconFileName() { return null; }
    @Override public String getDisplayName() { return "Spec history"; }
    @Override public String getUrlName() { return "playwright-specs"; }

    public String getDashboardUrl() { return job.getUrl() + "playwright-trends/"; }

    public Model getModel() {
        Model model = build(job);
        String requested = Stapler.getCurrentRequest2().getParameter("spec");
        model.select(requested);
        return model;
    }

    static Model build(Job<?, ?> job) {
        job.checkPermission(Item.READ);
        List<Build> builds = new ArrayList<>();
        Manifest baseline = null;
        InsightsAction baselineAction = null;
        for (Run<?, ?> run : job.getBuilds()) {
            InsightsAction action = run.getAction(InsightsAction.class);
            if (action == null) continue;
            Manifest manifest;
            try { manifest = action.manifest(); } catch (RuntimeException ignored) { continue; }
            if (!manifest.complete() || manifest.comparisonKey().isEmpty()) continue;
            if (baseline == null) { baseline = manifest; baselineAction = action; }
            if (!baselineAction.comparable(baseline, manifest)) continue;
            Build build = new Build(run.getDisplayName(), run.getUrl(), manifest);
            build.index = builds.size();
            builds.add(build);
        }

        Map<String, SpecRow> specs = new LinkedHashMap<>();
        for (Build build : builds) {
            Map<String, List<InsightsAction.TestRow>> bySpec = new LinkedHashMap<>();
            for (Object value : build.manifest.tests()) {
                InsightsAction.TestRow test = new InsightsAction.TestRow((JSONObject) value);
                String key = test.getProjectId() + "\0" + test.getFile();
                SpecRow spec = specs.computeIfAbsent(key, ignored -> new SpecRow(test.getProjectId(), test.getFile()));
                bySpec.computeIfAbsent(key, ignored -> new ArrayList<>()).add(test);
                spec.tests.computeIfAbsent(test.getId(), ignored -> new TestHistory(test.getId(), test.getTitle(), test.getLine()))
                        .runs.add(new Outcome(build, classify(test), test.getDurationDisplay(), test.getLinkId()));
            }
            bySpec.forEach((key, tests) -> specs.get(key).runs.add(new Outcome(build, classify(tests), "", "")));
        }
        List<SpecRow> rows = new ArrayList<>(specs.values());
        rows.forEach(spec -> {
            fillMissing(spec.runs, builds);
            spec.tests.values().forEach(test -> {
                fillMissing(test.runs, builds);
                test.health = health(test.runs, builds.size());
            });
            spec.health = specHealth(spec, builds.size());
        });
        rows.sort((a, b) -> {
            int severity = Integer.compare(rank(a.health), rank(b.health));
            return severity != 0 ? severity : (a.project + a.file).compareToIgnoreCase(b.project + b.file);
        });
        Summary summary = new Summary(builds.size(), rows);
        return new Model(rows, summary);
    }

    private static String classify(List<InsightsAction.TestRow> tests) {
        boolean flaky = false, expectedFailure = false, passed = false;
        for (InsightsAction.TestRow test : tests) {
            String result = classify(test);
            if (result.equals("failed")) return "failed";
            if (result.equals("flaky")) flaky = true;
            if (result.equals("expectedFailure")) expectedFailure = true;
            if (result.equals("passed")) passed = true;
        }
        if (flaky) return "flaky";
        if (passed) return "passed";
        if (expectedFailure) return "expectedFailure";
        return "skipped";
    }

    private static String classify(InsightsAction.TestRow test) {
        if (test.getOutcome().equals("unexpected")) return "failed";
        if (test.getOutcome().equals("flaky")) return "flaky";
        if (test.getOutcome().equals("skipped")) return "skipped";
        return test.getExpectedStatus().equals("failed") ? "expectedFailure" : "passed";
    }

    private static String health(List<Outcome> runs, int retainedRuns) {
        if (runs.isEmpty()) return "absent";
        if (runs.get(0).status.equals("absent")) return "absent";
        String latest = runs.get(0).status;
        if (latest.equals("failed")) return "failed";
        if (latest.equals("skipped")) return "skipped";
        if (latest.equals("expectedFailure")) return "expectedFailure";
        if (hasRetry(runs)) return "retryObserved";
        if (hasMixedOutcomes(runs)) return "mixedHistory";
        return retainedRuns > 1 && runs.stream().allMatch(row -> row.status.equals("passed")) ? "alwaysPassing" : "passed";
    }

    private static boolean hasRetry(List<Outcome> runs) {
        return runs.stream().anyMatch(row -> row.status.equals("flaky"));
    }

    private static boolean hasMixedOutcomes(List<Outcome> runs) {
        return runs.stream().anyMatch(row -> row.status.equals("failed"))
                && runs.stream().anyMatch(row -> row.status.equals("passed") || row.status.equals("expectedFailure") || row.status.equals("flaky"));
    }

    private static String specHealth(SpecRow spec, int retainedRuns) {
        String latest = spec.runs.get(0).status;
        if (latest.equals("absent") || latest.equals("failed") || latest.equals("skipped") || latest.equals("expectedFailure")) return latest;
        if (spec.isRetryObserved()) return "retryObserved";
        if (spec.isMixedHistory()) return "mixedHistory";
        return retainedRuns > 1 && spec.tests.values().stream().allMatch(test ->
                test.runs.stream().allMatch(row -> row.status.equals("passed"))) ? "alwaysPassing" : "passed";
    }

    private static void fillMissing(List<Outcome> outcomes, List<Build> builds) {
        Map<Integer, Outcome> observed = new LinkedHashMap<>();
        for (Outcome outcome : outcomes) observed.put(outcome.build.index, outcome);
        outcomes.clear();
        for (Build build : builds) outcomes.add(observed.getOrDefault(build.index, new Outcome(build, "absent", "", "")));
    }

    private static int rank(String health) {
        return switch (health) {
            case "failed" -> 0;
            case "retryObserved" -> 1;
            case "mixedHistory" -> 2;
            case "expectedFailure" -> 3;
            case "skipped" -> 4;
            case "passed" -> 5;
            case "alwaysPassing" -> 6;
            default -> 7;
        };
    }

    private static class Build {
        final String name, url;
        final Manifest manifest;
        int index;
        Build(String name, String url, Manifest manifest) { this.name = name; this.url = url; this.manifest = manifest; }
    }

    public static class Model {
        private final List<SpecRow> specs;
        private final Summary summary;
        private SpecRow selected;
        Model(List<SpecRow> specs, Summary summary) { this.specs = specs; this.summary = summary; select(null); }
        void select(String id) {
            selected = specs.stream().filter(spec -> spec.id.equals(id)).findFirst().orElse(specs.isEmpty() ? null : specs.get(0));
        }
        public List<SpecRow> getSpecs() { return specs; }
        public Summary getSummary() { return summary; }
        public SpecRow getSelected() { return selected; }
    }

    public static class Summary {
        private final int runs, total, alwaysPassing, failing, retrySpecs, mixedSpecs;
        Summary(int runs, List<SpecRow> specs) {
            this.runs = runs;
            total = specs.size();
            alwaysPassing = (int) specs.stream().filter(row -> row.health.equals("alwaysPassing")).count();
            failing = (int) specs.stream().filter(row -> row.health.equals("failed")).count();
            retrySpecs = (int) specs.stream().filter(SpecRow::isRetryObserved).count();
            mixedSpecs = (int) specs.stream().filter(SpecRow::isMixedHistory).count();
        }
        public int getRuns() { return runs; }
        public int getTotal() { return total; }
        public int getAlwaysPassing() { return alwaysPassing; }
        public int getFailing() { return failing; }
        public int getRetrySpecs() { return retrySpecs; }
        public int getMixedSpecs() { return mixedSpecs; }
    }

    public static class SpecRow {
        private final String id, project, file;
        private final List<Outcome> runs = new ArrayList<>();
        private final Map<String, TestHistory> tests = new LinkedHashMap<>();
        private String health;
        SpecRow(String project, String file) {
            this.project = project;
            this.file = file;
            id = UUID.nameUUIDFromBytes((project + "\0" + file).getBytes(StandardCharsets.UTF_8)).toString();
        }
        public String getId() { return id; }
        public String getProject() { return project; }
        public String getFile() { return file; }
        public String getHealth() { return health; }
        public String getHealthLabel() { return label(health); }
        public String getLatestStatus() { return runs.get(0).status.equals("flaky") ? "passed" : runs.get(0).status; }
        public String getLatestLabel() { return label(getLatestStatus()); }
        public List<Outcome> getRuns() { return runs; }
        public List<TestHistory> getTests() { return new ArrayList<>(tests.values()); }
        public int getTestCount() { return tests.size(); }
        public int getObservedRuns() { return (int) runs.stream().filter(row -> !row.status.equals("absent")).count(); }
        public boolean isRetryObserved() { return tests.values().stream().anyMatch(test -> hasRetry(test.runs)); }
        public boolean isMixedHistory() { return tests.values().stream().anyMatch(test -> hasMixedOutcomes(test.runs)); }
    }

    public static class TestHistory {
        private final String id, title;
        private final int line;
        private final List<Outcome> runs = new ArrayList<>();
        private String health;
        TestHistory(String id, String title, int line) { this.id = id; this.title = title; this.line = line; }
        public String getId() { return id; }
        public String getTitle() { return title; }
        public int getLine() { return line; }
        public String getHealth() { return health; }
        public String getHealthLabel() { return label(health); }
        public List<Outcome> getRuns() { return runs; }
        public boolean isRetryObserved() { return hasRetry(runs); }
        public boolean isMixedHistory() { return hasMixedOutcomes(runs); }
    }

    public static class Outcome {
        private final Build build;
        private final String status, duration, testId;
        Outcome(Build build, String status, String duration, String testId) {
            this.build = build; this.status = status; this.duration = duration; this.testId = testId;
        }
        public String getBuild() { return build.name; }
        public String getStatus() { return status; }
        public String getLabel() {
            if (status.equals("flaky")) return testId.isEmpty() ? "Contains retry pass" : "Passed after retry";
            return label(status);
        }
        public String getDuration() { return duration; }
        public String getUrl() { return build.url + "playwright-insights/" + (testId.isEmpty() ? "" : "?test=" + testId + "#test-detail"); }
    }

    private static String label(String status) {
        return switch (status) {
            case "alwaysPassing" -> "Always passing";
            case "failed" -> "Failing";
            case "flaky" -> "Passed after retry";
            case "retryObserved" -> "Retry observed";
            case "mixedHistory" -> "Mixed history";
            case "skipped" -> "Skipped";
            case "expectedFailure" -> "Expected failure";
            case "passed" -> "Passing";
            default -> "Not observed";
        };
    }
}
