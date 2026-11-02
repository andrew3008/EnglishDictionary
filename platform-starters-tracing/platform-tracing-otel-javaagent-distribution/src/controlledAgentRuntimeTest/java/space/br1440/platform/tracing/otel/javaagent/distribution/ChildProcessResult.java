package space.br1440.platform.tracing.otel.javaagent.distribution;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

record ChildProcessResult(boolean finished, Integer exitCode, String stdout, String stderr) {

    private static final ObjectMapper JSON = new ObjectMapper();

    void assertCompletedSuccessfully() {
        assertThat(finished).as("Controlled agent child JVM must terminate").isTrue();
        assertThat(exitCode)
                .as("Controlled agent child JVM failed; stdout=%s, stderr=%s", stdout, stderr)
                .isZero();
    }

    void assertStartupFailureSummary() throws IOException {
        List<JsonNode> summaries = new ArrayList<>();
        for (String line : stderr.lines().filter(value -> value.startsWith("{")).toList()) {
            JsonNode event = JSON.readTree(line);
            if ("STARTUP_FAILED".equals(event.path("event_name").asText())) {
                summaries.add(event);
            }
        }

        assertThat(summaries)
                .as("Child stderr must contain exactly one startup failure summary: %s", stderr)
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.path("level").asText()).isEqualTo("ERROR");
                    assertThat(event.path("event_code").asText()).isEqualTo("PTA-BOOT-012");
                });
    }
}
