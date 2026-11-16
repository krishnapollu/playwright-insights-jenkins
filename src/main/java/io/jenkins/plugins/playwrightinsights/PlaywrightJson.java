package io.jenkins.plugins.playwrightinsights;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

/** Converts Playwright's built-in JSON report to the validated snapshot format. Runs on the agent. */
final class PlaywrightJson {
    private static final String SAFE_PATH = "^(?!\\.{1,2}(?:/|$))[A-Za-z0-9_@()+, .=-]+(/(?!\\.{1,2}(?:/|$))[A-Za-z0-9_@()+, .=-]+)*$";
    private final Path workspace;
    private final JSONArray warnings = new JSONArray();
    private final JSONArray tests = new JSONArray();
    private final Map<String, String> projects = new LinkedHashMap<>();
    private String rootDir;

    private PlaywrightJson(Path workspace) { this.workspace = workspace; }

    static byte[] normalize(byte[] bytes, Path workspace, String comparisonKey) throws IOException {
        if (bytes.length > Manifest.MAX_BYTES) throw new IOException("report exceeds 25 MiB");
        JSONObject source;
        try {
            source = JSONObject.fromObject(new String(bytes, StandardCharsets.UTF_8));
        } catch (RuntimeException error) {
            throw new IOException("invalid JSON report", error);
        }
        if (source.containsKey("schemaVersion")) return bytes;
        if (!source.containsKey("config") || !source.containsKey("suites") || !source.containsKey("stats"))
            throw new IOException("expected a Playwright JSON report or Playwright Insights manifest");
        try {
            return new PlaywrightJson(workspace).convert(source, comparisonKey);
        } catch (RuntimeException error) {
            throw new IOException("invalid Playwright JSON report", error);
        }
    }

    private byte[] convert(JSONObject source, String comparisonKey) throws IOException {
        JSONObject config = source.getJSONObject("config");
        JSONObject stats = source.getJSONObject("stats");
        rootDir = config.optString("rootDir", "");
        JSONArray nativeProjects = config.getJSONArray("projects");
        for (Object value : nativeProjects) {
            JSONObject project = (JSONObject) value;
            String nativeId = project.optString("id", "");
            String name = project.optString("name", "");
            projects.put(nativeId, bounded(name.isEmpty() ? "default" : name, 128));
        }
        if (projects.isEmpty()) projects.put("", "default");
        JSONArray normalizedProjects = new JSONArray();
        for (String name : projects.values().stream().distinct().toList()) normalizedProjects.add(object("id", name, "name", name));
        for (Object suite : source.getJSONArray("suites")) visitSuite((JSONObject) suite, List.of());

        int expectedCount = stats.optInt("expected", 0) + stats.optInt("unexpected", 0)
                + stats.optInt("flaky", 0) + stats.optInt("skipped", 0);
        boolean complete = source.getJSONArray("errors").isEmpty() && tests.size() == expectedCount
                && tests.stream().noneMatch(value -> ((JSONObject) value).getJSONArray("attempts").stream()
                        .anyMatch(attempt -> "interrupted".equals(((JSONObject) attempt).getString("status"))));
        if (tests.size() != expectedCount) warn("Report does not contain every test; disable JSON onlyFailures for a complete view");
        JSONObject run = object("id", UUID.randomUUID().toString(),
                "startedAt", Instant.parse(stats.getString("startTime")).toString(),
                "durationMs", duration(stats.optDouble("duration", 0)),
                "complete", complete);
        if (comparisonKey != null && !comparisonKey.isBlank()) run.put("comparisonKey", bounded(comparisonKey, 256));
        JSONObject manifest = object("schemaVersion", "1.1.0", "producer", object("name", "playwright-json", "version", bounded(config.optString("version", "unknown"), 64)),
                "run", run, "projects", normalizedProjects, "tests", tests, "warnings", warnings);
        byte[] result = manifest.toString().getBytes(StandardCharsets.UTF_8);
        if (result.length > Manifest.MAX_BYTES) throw new IOException("normalized report exceeds 25 MiB");
        Manifest.parse(result);
        return result;
    }

    private void visitSuite(JSONObject suite, List<String> parents) {
        List<String> titles = new ArrayList<>(parents);
        String title = suite.optString("title", "");
        if (!title.isEmpty() && (suite.optString("file", "").isEmpty() || !title.equals(suite.optString("file")))) titles.add(title);
        for (Object value : suite.optJSONArray("specs") == null ? new JSONArray() : suite.getJSONArray("specs")) visitSpec((JSONObject) value, titles);
        for (Object value : suite.optJSONArray("suites") == null ? new JSONArray() : suite.getJSONArray("suites")) visitSuite((JSONObject) value, titles);
    }

    private void visitSpec(JSONObject spec, List<String> parents) {
        List<String> titles = new ArrayList<>(parents);
        titles.add(spec.getString("title"));
        JSONArray titlePath = new JSONArray();
        for (String title : titles.stream().limit(32).toList()) titlePath.add(bounded(title, 512));
        Map<String, Integer> repeats = new LinkedHashMap<>();
        for (Object value : spec.getJSONArray("tests")) {
            JSONObject nativeTest = (JSONObject) value;
            String nativeProject = nativeTest.optString("projectId", "");
            String project = projects.getOrDefault(nativeProject, "default");
            int repeatEachIndex = nativeTest.optInt("repeatEachIndex", repeats.getOrDefault(nativeProject, 0));
            repeats.put(nativeProject, repeatEachIndex + 1);
            String file = sourcePath(spec.optString("file", ""));
            String id = hash(project + "\u0000" + spec.optString("file", "") + "\u0000" + titlePath + "\u0000" + repeatEachIndex);
            JSONArray attempts = new JSONArray();
            JSONArray results = nativeTest.getJSONArray("results");
            for (int i = 0; i < Math.min(16, results.size()); i++) attempts.add(attempt((JSONObject) results.get(i), i));
            JSONArray tags = new JSONArray();
            JSONArray nativeTags = spec.optJSONArray("tags");
            if (nativeTags != null) for (Object tag : nativeTags.stream().limit(16).toList()) {
                String text = String.valueOf(tag);
                tags.add(bounded(text.startsWith("@") ? text : "@" + text, 128));
            }
            JSONArray annotations = new JSONArray();
            JSONArray nativeAnnotations = nativeTest.optJSONArray("annotations");
            if (nativeAnnotations != null) for (Object item : nativeAnnotations.stream().limit(16).toList()) {
                JSONObject annotation = (JSONObject) item;
                JSONObject row = object("type", bounded(annotation.optString("type", "annotation"), 128));
                if (annotation.containsKey("description")) row.put("description", bounded(annotation.optString("description", ""), 512));
                annotations.add(row);
            }
            JSONObject test = object("id", id, "projectId", project, "file", file,
                    "line", Math.max(1, spec.optInt("line", 1)), "titlePath", titlePath,
                    "repeatEachIndex", Math.max(0, repeatEachIndex),
                    "expectedStatus", nativeTest.optString("expectedStatus", "passed"),
                    "outcome", nativeTest.optString("status", "expected"),
                    "attempts", attempts, "tags", tags, "annotations", annotations);
            tests.add(test);
        }
    }

    private JSONObject attempt(JSONObject source, int retry) {
        JSONArray errors = new JSONArray();
        JSONArray nativeErrors = source.optJSONArray("errors");
        if (nativeErrors != null) for (Object value : nativeErrors.stream().limit(8).toList()) {
            JSONObject error = (JSONObject) value;
            JSONObject row = object("message", bounded(error.optString("message", "Unknown error"), 8192));
            if (error.containsKey("stack")) row.put("stack", bounded(error.optString("stack", ""), 32768));
            errors.add(row);
        }
        JSONArray steps = new JSONArray();
        addSteps(source.optJSONArray("steps"), steps, 0);
        JSONArray attachments = new JSONArray();
        JSONArray nativeAttachments = source.optJSONArray("attachments");
        if (nativeAttachments != null) for (Object value : nativeAttachments.stream().limit(16).toList()) {
            JSONObject attachment = (JSONObject) value;
            String path = attachmentPath(attachment);
            if (path != null) attachments.add(object("name", bounded(attachment.optString("name", "attachment"), 256),
                    "contentType", bounded(attachment.optString("contentType", "application/octet-stream"), 128), "path", path));
        }
        JSONObject row = object("retry", retry, "status", source.optString("status", "skipped"),
                "durationMs", duration(source.optDouble("duration", 0)), "errors", errors,
                "attachments", attachments, "steps", steps, "workerIndex", Math.max(-1, source.optInt("workerIndex", -1)));
        for (String stream : List.of("stdout", "stderr")) {
            JSONArray chunks = source.optJSONArray(stream);
            if (chunks != null) {
                StringBuilder output = new StringBuilder();
                for (Object chunk : chunks) {
                    if (chunk instanceof String) output.append(chunk);
                    else if (chunk instanceof JSONObject) output.append(((JSONObject) chunk).optString("text", ""));
                }
                if (!output.isEmpty()) row.put(stream, bounded(output.toString(), 4096));
            }
        }
        return row;
    }

    private void addSteps(JSONArray source, JSONArray target, int depth) {
        if (source == null || depth > 4) return;
        for (Object value : source) {
            if (target.size() >= 128) return;
            JSONObject step = (JSONObject) value;
            JSONObject row = object("title", bounded(step.optString("title", "Untitled step"), 256),
                    "category", bounded(step.optString("category", "other"), 64),
                    "durationMs", duration(step.optDouble("duration", 0)), "depth", depth);
            JSONObject error = step.optJSONObject("error");
            if (error != null) row.put("error", bounded(error.optString("message", "Step failed"), 1024));
            target.add(row);
            addSteps(step.optJSONArray("steps"), target, depth + 1);
        }
    }

    private String sourcePath(String file) {
        try {
            Path path = Path.of(rootDir).resolve(file).toRealPath();
            if (path.startsWith(workspace)) {
                String relative = safe(workspace.relativize(path).toString());
                if (relative != null) return relative;
            }
        } catch (IOException | RuntimeException ignored) { }
        String fallback = safe(file);
        if (fallback != null) return fallback;
        warn("Test source path was omitted");
        return "unknown";
    }

    private String attachmentPath(JSONObject attachment) {
        String path = attachment.optString("path", "");
        if (!path.isEmpty()) {
            try {
                Path file = Path.of(path);
                if (!file.isAbsolute()) file = workspace.resolve(file);
                file = file.toRealPath();
                if (file.startsWith(workspace) && Files.isRegularFile(file)) {
                    String relative = safe(workspace.relativize(file).toString());
                    if (relative != null) return relative;
                }
            } catch (IOException | RuntimeException ignored) { }
        } else if (attachment.containsKey("body")) {
            String contentType = attachment.optString("contentType", "");
            String suffix = switch (contentType) {
                case "image/png" -> ".png";
                case "image/jpeg" -> ".jpg";
                case "image/webp" -> ".webp";
                case "video/webm" -> ".webm";
                case "video/mp4" -> ".mp4";
                default -> null;
            };
            if (suffix != null) try {
                byte[] body = Base64.getDecoder().decode(attachment.getString("body"));
                Path directory = workspace.resolve("test-results/playwright-insights");
                Files.createDirectories(directory);
                if (!directory.toRealPath().startsWith(workspace)) throw new IOException("unsafe attachment directory");
                Path output = directory.resolve(UUID.randomUUID() + suffix);
                Files.write(output, body);
                return safe(workspace.relativize(output).toString());
            } catch (IOException | RuntimeException ignored) { }
        }
        warn("Attachment omitted: " + bounded(attachment.optString("name", "unnamed"), 100));
        return null;
    }

    private void warn(String message) { if (warnings.size() < 100) warnings.add(message); }
    private static String safe(String path) {
        String normalized = path.replace('\\', '/');
        return normalized.length() <= 1024 && normalized.matches(SAFE_PATH) ? normalized : null;
    }
    private static long duration(double value) { return Double.isFinite(value) ? Math.max(0, Math.round(value)) : 0; }
    private static String bounded(String value, int max) { return value.isEmpty() ? "?" : value.substring(0, Math.min(value.length(), max)); }
    private static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private static JSONObject object(Object... pairs) {
        JSONObject result = new JSONObject();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }
}
