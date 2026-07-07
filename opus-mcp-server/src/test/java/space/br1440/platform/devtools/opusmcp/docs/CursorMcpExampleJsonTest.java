package space.br1440.platform.devtools.opusmcp.docs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import space.br1440.platform.devtools.opusmcp.testsupport.ProjectPaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates that the Cursor MCP config example is valid, comment-free JSON, copy-friendly, and
 * contains no API key. Protects Cursor integration from a broken example.
 */
class CursorMcpExampleJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private Path file() {
        return ProjectPaths.resolve("docs/cursor-mcp.example.json");
    }

    private String content() throws Exception {
        return Files.readString(file());
    }

    @Test
    void exampleFileExists() {
        assertThat(Files.exists(file())).isTrue();
    }

    @Test
    void exampleIsValidJsonWithoutComments() throws Exception {
        String raw = content();
        // Valid JSON parse (would throw on JS-style comments).
        JsonNode root = mapper.readTree(raw);
        assertThat(root.isObject()).isTrue();
        // No JS-style block comments and no "//"-style pseudo-comment keys
        // (note: "//" legitimately appears inside the https:// URL value, so we check key form).
        assertThat(raw).doesNotContain("\"//\"");
        assertThat(raw).doesNotContainPattern("\"//[a-zA-Z]*\"\\s*:");
        assertThat(raw).doesNotContain("/*");
    }

    @Test
    void exampleHasExpectedServerWiring() throws Exception {
        JsonNode server = mapper.readTree(content())
                .path("mcpServers").path("java-opus-mcp");
        assertThat(server.path("command").asText()).isEqualTo("java");

        List<String> args = new ArrayList<>();
        server.path("args").forEach(n -> args.add(n.asText()));
        assertThat(args).contains("-jar");

        JsonNode env = server.path("env");
        assertThat(env.has("OPUS_BASE_URL")).isTrue();
        assertThat(env.has("OPUS_MODEL")).isTrue();
        assertThat(env.path("OPUS_MODEL").asText()).isEqualTo("claude-opus-4-8");
    }

    @Test
    void exampleDoesNotContainApiKey() throws Exception {
        assertThat(content()).doesNotContain("OPUS_API_KEY");
    }
}
