package io.jenkins.plugins.playwrightinsights;

import hudson.AbortException;
import hudson.Extension;
import hudson.FilePath;
import hudson.model.Run;
import hudson.model.TaskListener;
import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import jenkins.MasterToSlaveFileCallable;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.steps.SynchronousStepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

public class PlaywrightInsightsStep extends Step {
    private final String results;
    private boolean failOnImportError;
    private String comparisonKey;

    @DataBoundConstructor
    public PlaywrightInsightsStep(String results) {
        this.results = results;
    }

    public String getResults() { return results; }
    public boolean isFailOnImportError() { return failOnImportError; }
    public String getComparisonKey() { return comparisonKey; }

    @DataBoundSetter
    public void setFailOnImportError(boolean value) { failOnImportError = value; }

    @DataBoundSetter
    public void setComparisonKey(String value) { comparisonKey = value; }

    @Override
    public StepExecution start(StepContext context) {
        return new Execution(this, context);
    }

    private static class Execution extends SynchronousStepExecution<Void> {
        private static final long serialVersionUID = 1L;
        private final String results;
        private final boolean failOnImportError;
        private final String comparisonKey;

        Execution(PlaywrightInsightsStep step, StepContext context) {
            super(context);
            results = step.results;
            failOnImportError = step.failOnImportError;
            comparisonKey = step.comparisonKey;
        }

        @Override
        protected Void run() throws Exception {
            TaskListener listener = getContext().get(TaskListener.class);
            Run<?, ?> run = getContext().get(Run.class);
            try {
                if (run.getAction(InsightsAction.class) != null) throw new IOException("one manifest per build is supported");
                byte[] bytes = getContext().get(FilePath.class).act(new ReadManifest(results, comparisonKey));
                Manifest manifest = Manifest.parse(bytes);
                InsightsAction.save(run, bytes, manifest);
                listener.getLogger().printf("Playwright Insights: imported %d logical tests (%d bytes, %d warnings)%n",
                        manifest.tests().size(), bytes.length, manifest.warnings().size());
            } catch (Exception error) {
                String message = "Playwright Insights import warning: " + error.getMessage();
                listener.getLogger().println(message);
                if (failOnImportError) throw new AbortException(message);
            }
            return null;
        }
    }

    private static class ReadManifest extends MasterToSlaveFileCallable<byte[]> implements Serializable {
        private static final long serialVersionUID = 1L;
        private final String path;
        private final String comparisonKey;

        ReadManifest(String path, String comparisonKey) {
            this.path = path;
            this.comparisonKey = comparisonKey;
        }

        @Override
        public byte[] invoke(java.io.File workspace, hudson.remoting.VirtualChannel channel) throws IOException {
            if (path == null || !path.matches("^(?!\\.{1,2}(?:/|$))[A-Za-z0-9_@()+, .=-]+(/(?!\\.{1,2}(?:/|$))[A-Za-z0-9_@()+, .=-]+)*$")) {
                throw new IOException("results must be a workspace-relative manifest path");
            }
            Path root = workspace.toPath().toRealPath();
            Path file = root.resolve(path).normalize();
            if (!Files.exists(file)) throw new IOException("manifest file not found");
            if (!file.toRealPath().startsWith(root) || !Files.isRegularFile(file)) throw new IOException("manifest is outside the workspace or not a file");
            if (Files.size(file) > Manifest.MAX_BYTES) throw new IOException("manifest exceeds 25 MiB");
            return PlaywrightJson.normalize(Files.readAllBytes(file), root, comparisonKey);
        }
    }

    @Extension
    public static class DescriptorImpl extends StepDescriptor {
        @Override public String getFunctionName() { return "playwrightInsights"; }
        @Override public String getDisplayName() { return "Import Playwright Insights manifest"; }
        @Override public Set<? extends Class<?>> getRequiredContext() {
            return Set.of(Run.class, FilePath.class, TaskListener.class);
        }
    }
}
