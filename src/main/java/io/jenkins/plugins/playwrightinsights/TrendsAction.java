package io.jenkins.plugins.playwrightinsights;

import hudson.Extension;
import hudson.model.Action;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.Run;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import jenkins.model.TransientActionFactory;

public class TrendsAction implements Action {
    private final Job<?, ?> job;

    private TrendsAction(Job<?, ?> job) { this.job = job; }

    @Override public String getIconFileName() { return "graph.png"; }
    @Override public String getDisplayName() { return "Playwright Insights"; }
    @Override public String getUrlName() { return "playwright-trends"; }

    public List<TrendRow> getRows() {
        job.checkPermission(Item.READ);
        Manifest baseline = null;
        InsightsAction baselineAction = null;
        List<TrendRow> rows = new ArrayList<>();
        int examined = 0;
        for (Run<?, ?> run : job.getBuilds()) {
            if (++examined > 50 || rows.size() == 10) break;
            InsightsAction action = run.getAction(InsightsAction.class);
            if (action == null) continue;
            Manifest manifest;
            try { manifest = action.manifest(); } catch (RuntimeException ignored) { continue; }
            if (!manifest.complete() || manifest.comparisonKey().isEmpty()) continue;
            if (baseline == null) { baseline = manifest; baselineAction = action; }
            if (!baselineAction.comparable(baseline, manifest)) continue;
            rows.add(new TrendRow(run.getDisplayName(), run.getUrl(), manifest.run().getString("startedAt"),
                    manifest.run().getLong("durationMs"), action.getRecordedAssertions(), action.getStatusCounts(), action.getCounts()));
        }
        return rows;
    }

    public static class TrendRow {
        private final String build;
        private final String url;
        private final String startedAt;
        private final long durationMs;
        private final int recordedAssertions;
        private final java.util.Map<String, Integer> statuses;
        private final java.util.Map<String, Integer> outcomes;
        TrendRow(String build, String url, String startedAt, long durationMs, int recordedAssertions,
                java.util.Map<String, Integer> statuses, java.util.Map<String, Integer> outcomes) {
            this.build = build;
            this.url = url;
            this.startedAt = startedAt;
            this.durationMs = durationMs;
            this.recordedAssertions = recordedAssertions;
            this.statuses = statuses;
            this.outcomes = outcomes;
        }
        public String getBuild() { return build; }
        public String getUrl() { return url; }
        public String getStartedAt() { return startedAt; }
        public String getDurationDisplay() { return InsightsAction.formatDuration(durationMs); }
        public int getRecordedAssertions() { return recordedAssertions; }
        public int getPassed() { return statuses.getOrDefault("passed", 0); }
        public int getFailed() { return statuses.getOrDefault("failed", 0); }
        public int getTimedOut() { return statuses.getOrDefault("timedOut", 0); }
        public int getSkipped() { return statuses.getOrDefault("skipped", 0); }
        public int getInterrupted() { return statuses.getOrDefault("interrupted", 0); }
        public int getUnexpected() { return outcomes.getOrDefault("unexpected", 0); }
        public int getFlaky() { return outcomes.getOrDefault("flaky", 0); }
        public int getTotal() { return getPassed() + getFailed() + getTimedOut() + getSkipped() + getInterrupted(); }
        public int getUnexpectedPercent() { return getTotal() == 0 ? 0 : Math.round(getUnexpected() * 100f / getTotal()); }
    }

    @Extension
    public static class Factory extends TransientActionFactory<Job> {
        @Override public Class<Job> type() { return Job.class; }
        @Override public Collection<? extends Action> createFor(Job job) {
            int examined = 0;
            for (Object value : job.getBuilds()) {
                if (++examined > 20) break;
                if (((Run<?, ?>) value).getAction(InsightsAction.class) != null) return List.of(new TrendsAction(job));
            }
            return List.of();
        }
    }
}
