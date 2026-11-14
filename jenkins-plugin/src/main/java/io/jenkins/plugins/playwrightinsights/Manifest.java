package io.jenkins.plugins.playwrightinsights;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

final class Manifest {
    static final int MAX_BYTES = 25 * 1024 * 1024;
    private static final String PATH = "^(?!\\.{1,2}(?:/|$))[A-Za-z0-9_@()+, .=-]+(/(?!\\.{1,2}(?:/|$))[A-Za-z0-9_@()+, .=-]+)*$";
    private final JSONObject root;

    private Manifest(JSONObject root) { this.root = root; }

    static Manifest parse(byte[] bytes) throws IOException {
        if (bytes.length > MAX_BYTES) throw new IOException("manifest exceeds 25 MiB");
        try {
            JSONObject root = JSONObject.fromObject(new String(bytes, StandardCharsets.UTF_8));
            validate(root);
            return new Manifest(root);
        } catch (ManifestValidationException error) {
            throw new IOException("invalid manifest: " + error.getMessage(), error);
        } catch (RuntimeException error) {
            throw new IOException("invalid JSON manifest", error);
        }
    }

    JSONObject root() { return root; }
    JSONObject run() { return root.getJSONObject("run"); }
    JSONArray tests() { return root.getJSONArray("tests"); }
    JSONArray projects() { return root.getJSONArray("projects"); }
    JSONArray warnings() { return root.getJSONArray("warnings"); }
    boolean complete() { return run().getBoolean("complete"); }
    String comparisonKey() { return run().optString("comparisonKey", ""); }

    private static void validate(JSONObject root) {
        fields(root, "schemaVersion producer run projects tests warnings", "");
        if (!"1.0.0".equals(string(root, "schemaVersion", 1, 16))) fail("unsupported schema version");
        JSONObject producer = object(root, "producer");
        fields(producer, "name version", "");
        string(producer, "name", 1, 128);
        string(producer, "version", 1, 64);

        JSONObject run = object(root, "run");
        fields(run, "id startedAt durationMs complete", "comparisonKey metadata");
        string(run, "id", 1, 128);
        if (!string(run, "startedAt", 20, 24).matches("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{3})?Z$")) fail("invalid startedAt");
        integer(run, "durationMs", 0, Long.MAX_VALUE);
        if (!(run.get("complete") instanceof Boolean)) fail("run.complete must be boolean");
        if (run.containsKey("comparisonKey")) string(run, "comparisonKey", 1, 256);
        if (run.containsKey("metadata")) {
            JSONObject metadata = object(run, "metadata");
            fields(metadata, "", "branch commit selection");
            for (String key : List.of("branch", "commit", "selection")) {
                if (metadata.containsKey(key)) string(metadata, key, 0, key.equals("selection") ? 512 : key.equals("branch") ? 256 : 128);
            }
        }

        JSONArray projects = array(root, "projects", 128);
        Set<String> projectIds = new HashSet<>();
        for (Object value : projects) {
            JSONObject project = asObject(value, "project");
            fields(project, "id name", "");
            String id = string(project, "id", 1, 128);
            string(project, "name", 1, 256);
            if (!projectIds.add(id)) fail("duplicate project id");
        }

        JSONArray tests = array(root, "tests", 20_000);
        Set<String> testIds = new HashSet<>();
        for (Object value : tests) {
            JSONObject test = asObject(value, "test");
            fields(test, "id projectId file line titlePath repeatEachIndex expectedStatus outcome attempts", "");
            String id = string(test, "id", 1, 128);
            if (!testIds.add(id)) fail("duplicate test id");
            if (!projectIds.contains(string(test, "projectId", 1, 128))) fail("unknown project id");
            path(test, "file");
            integer(test, "line", 1, Long.MAX_VALUE);
            JSONArray titles = array(test, "titlePath", 32);
            if (titles.isEmpty()) fail("empty title path");
            for (Object title : titles) boundedString(title, 1, 512, "title");
            integer(test, "repeatEachIndex", 0, Long.MAX_VALUE);
            choice(test, "expectedStatus", "passed", "failed", "timedOut", "skipped", "interrupted");
            choice(test, "outcome", "expected", "unexpected", "flaky", "skipped");
            JSONArray attempts = array(test, "attempts", 16);
            for (int i = 0; i < attempts.size(); i++) {
                JSONObject attempt = asObject(attempts.get(i), "attempt");
                fields(attempt, "retry status durationMs errors attachments", "");
                if (integer(attempt, "retry", 0, 15) != i) fail("nonsequential retry");
                choice(attempt, "status", "passed", "failed", "timedOut", "skipped", "interrupted");
                integer(attempt, "durationMs", 0, Long.MAX_VALUE);
                for (Object errorValue : array(attempt, "errors", 8)) {
                    JSONObject error = asObject(errorValue, "error");
                    fields(error, "message", "stack");
                    string(error, "message", 0, 8192);
                    if (error.containsKey("stack")) string(error, "stack", 0, 32768);
                }
                for (Object attachmentValue : array(attempt, "attachments", 16)) {
                    JSONObject attachment = asObject(attachmentValue, "attachment");
                    fields(attachment, "name contentType path", "");
                    string(attachment, "name", 1, 256);
                    string(attachment, "contentType", 1, 128);
                    path(attachment, "path");
                }
            }
        }
        for (Object warning : array(root, "warnings", 100)) boundedString(warning, 1, 1024, "warning");
    }

    private static void fields(JSONObject value, String required, String optional) {
        Set<String> mandatory = new HashSet<>();
        if (!required.isEmpty()) mandatory.addAll(List.of(required.split(" ")));
        Set<String> allowed = new HashSet<>(mandatory);
        if (!optional.isEmpty()) allowed.addAll(List.of(optional.split(" ")));
        for (String key : mandatory) if (!value.containsKey(key)) fail("missing " + key);
        for (Object key : value.keySet()) if (!allowed.contains(key)) fail("unknown field");
    }

    private static JSONObject object(JSONObject parent, String key) { return asObject(parent.get(key), key); }
    private static JSONObject asObject(Object value, String key) {
        if (!(value instanceof JSONObject)) fail(key + " must be object");
        return (JSONObject) value;
    }
    private static JSONArray array(JSONObject parent, String key, int maximum) {
        Object value = parent.get(key);
        if (!(value instanceof JSONArray array) || array.size() > maximum) fail(key + " must be array of at most " + maximum);
        return (JSONArray) value;
    }
    private static String string(JSONObject parent, String key, int min, int max) {
        return boundedString(parent.get(key), min, max, key);
    }
    private static String boundedString(Object value, int min, int max, String key) {
        if (!(value instanceof String text) || text.length() < min || text.length() > max) fail(key + " must be bounded string");
        return (String) value;
    }
    private static long integer(JSONObject parent, String key, long min, long max) {
        Object value = parent.get(key);
        if (!(value instanceof Number number) || number.doubleValue() % 1 != 0 || number.doubleValue() < min || number.doubleValue() > max) fail(key + " must be bounded integer");
        return ((Number) value).longValue();
    }
    private static void choice(JSONObject parent, String key, String... values) {
        if (!List.of(values).contains(string(parent, key, 1, 32))) fail("invalid " + key);
    }
    private static void path(JSONObject parent, String key) {
        if (!string(parent, key, 1, 1024).matches(PATH)) fail("unsafe " + key);
    }
    private static void fail(String reason) { throw new ManifestValidationException(reason); }

    private static class ManifestValidationException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        ManifestValidationException(String reason) { super(reason); }
    }
}
