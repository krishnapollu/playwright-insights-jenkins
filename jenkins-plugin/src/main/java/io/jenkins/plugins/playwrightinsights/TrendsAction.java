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
            rows.add(new TrendRow(run.getDisplayName(), run.getUrl(), manifest.run().getString("startedAt"), action.getCounts()));
        }
        return rows;
    }

    public static class TrendRow {
        private final String build;
        private final String url;
        private final String startedAt;
        private final java.util.Map<String, Integer> counts;
        TrendRow(String build, String url, String startedAt, java.util.Map<String, Integer> counts) {
            this.build = build;
            this.url = url;
            this.startedAt = startedAt;
            this.counts = counts;
        }
        public String getBuild() { return build; }
        public String getUrl() { return url; }
        public String getStartedAt() { return startedAt; }
        public int getExpected() { return counts.getOrDefault("expected", 0); }
        public int getUnexpected() { return counts.getOrDefault("unexpected", 0); }
        public int getFlaky() { return counts.getOrDefault("flaky", 0); }
        public int getSkipped() { return counts.getOrDefault("skipped", 0); }
        public int getTotal() { return getExpected() + getUnexpected() + getFlaky() + getSkipped(); }
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
