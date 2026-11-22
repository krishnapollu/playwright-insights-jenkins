package io.jenkins.plugins.playwrightinsights;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

/** Reads assertion events from the built-in Playwright blob report v2 without extracting its archive. */
final class PlaywrightBlob {
    private static final int MAX_BYTES = 50 * 1024 * 1024;
    record Data(long startedAt, Map<String, Map<Integer, JSONArray>> assertions) { }
    private record Result(String testId, int retry) { }
    private record Step(String resultId, String title) { }

    static Data read(Path file) throws IOException {
        if (Files.size(file) > MAX_BYTES) throw new IOException("blob report exceeds 50 MiB");
        byte[] report = null;
        try (ZipFile zip = new ZipFile(file.toFile())) {
            ZipEntry entry = zip.getEntry("report.jsonl");
            if (entry != null && !entry.isDirectory() && entry.getSize() <= MAX_BYTES) {
                try (InputStream input = zip.getInputStream(entry)) {
                    report = input.readNBytes(MAX_BYTES + 1);
                }
            }
        }
        if (report == null || report.length > MAX_BYTES) throw new IOException("blob report.jsonl is missing or too large");
        Map<String, Result> results = new HashMap<>();
        Map<String, Step> begins = new HashMap<>();
        Map<String, Map<Integer, JSONArray>> assertions = new HashMap<>();
        long startedAt = -1;
        boolean supported = false;
        try {
            for (String line : new String(report, StandardCharsets.UTF_8).split("\n")) {
                if (line.isBlank()) continue;
                JSONObject event = JSONObject.fromObject(line);
                JSONObject params = event.optJSONObject("params");
                if (params == null) continue;
                switch (event.optString("method", "")) {
                    case "onBlobReportMetadata" -> supported = params.optInt("version", -1) == 2;
                    case "onTestBegin" -> {
                        JSONObject result = params.getJSONObject("result");
                        results.put(result.getString("id"), new Result(params.getString("testId"), result.getInt("retry")));
                    }
                    case "onStepBegin" -> {
                        JSONObject step = params.getJSONObject("step");
                        if ("expect".equals(step.optString("category", "")))
                            begins.put(step.getString("id"), new Step(params.getString("resultId"), step.optString("title", "expect")));
                    }
                    case "onStepEnd" -> {
                        JSONObject step = params.getJSONObject("step");
                        Step begin = begins.remove(step.getString("id"));
                        if (begin == null) break;
                        Result result = results.get(begin.resultId());
                        if (result == null) break;
                        JSONArray rows = assertions.computeIfAbsent(result.testId(), ignored -> new HashMap<>())
                                .computeIfAbsent(result.retry(), ignored -> new JSONArray());
                        if (rows.size() >= 128) break;
                        JSONObject row = new JSONObject();
                        row.put("title", clip(begin.title(), 256));
                        row.put("category", "expect");
                        row.put("durationMs", Math.max(0, Math.round(step.optDouble("duration", 0))));
                        row.put("depth", 0);
                        JSONObject error = step.optJSONObject("error");
                        if (error != null) row.put("error", clip(error.optString("message", "Assertion failed"), 1024));
                        rows.add(row);
                    }
                    case "onEnd" -> startedAt = params.getJSONObject("result").getLong("startTime");
                    default -> { }
                }
            }
        } catch (RuntimeException error) {
            throw new IOException("invalid Playwright blob events", error);
        }
        if (!supported || startedAt < 0) throw new IOException("unsupported Playwright blob report version");
        return new Data(startedAt, assertions);
    }

    private static String clip(String value, int max) {
        if (value.isEmpty()) return "expect";
        return value.substring(0, Math.min(value.length(), max));
    }
}
