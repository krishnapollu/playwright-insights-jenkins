package io.jenkins.plugins.playwrightinsights;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.junit.jupiter.api.Test;

class ManifestTest {
    private String fixture(String name) throws IOException {
        return Files.readString(Path.of("..", "schema", "fixtures", name + ".json"));
    }

    private Manifest parse(String value) throws IOException {
        return Manifest.parse(value.getBytes(StandardCharsets.UTF_8));
    }

    @Test void acceptsRepresentativeFixtures() throws IOException {
        assertEquals(6, parse(fixture("outcomes")).tests().size());
        assertEquals(0, parse(fixture("empty")).tests().size());
        assertFalse(parse(fixture("incomplete")).complete());
    }

    @Test void rejectsUnsupportedVersionAndUnsafeAttachment() throws IOException {
        String valid = fixture("outcomes");
        assertThrows(IOException.class, () -> parse(valid.replace("1.0.0", "2.0.0")));
        assertThrows(IOException.class, () -> parse(valid.replace("test-results/fails/screenshot.png", "../secret")));
    }

    @Test void rejectsDuplicateIdentityAndOversizedFile() throws IOException {
        String valid = fixture("outcomes");
        assertThrows(IOException.class, () -> parse(valid.replace("fail-1", "pass-1")));
        assertThrows(IOException.class, () -> Manifest.parse(new byte[Manifest.MAX_BYTES + 1]));
    }

    @Test void acceptsFiveThousandLogicalTests() throws IOException {
        JSONObject root = JSONObject.fromObject(fixture("outcomes"));
        JSONObject template = root.getJSONArray("tests").getJSONObject(0);
        JSONArray tests = new JSONArray();
        for (int i = 0; i < 5_000; i++) {
            JSONObject result = JSONObject.fromObject(template.toString());
            result.put("id", "bulk-" + i);
            result.put("titlePath", JSONArray.fromObject(new String[] {"bulk-" + i}));
            tests.add(result);
        }
        root.put("tests", tests);
        assertEquals(5_000, parse(root.toString()).tests().size());
    }
}
