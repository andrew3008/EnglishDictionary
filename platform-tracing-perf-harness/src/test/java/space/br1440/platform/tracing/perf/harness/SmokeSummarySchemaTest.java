package space.br1440.platform.tracing.perf.harness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SmokeSummarySchemaTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void sampleSummary_matchesSmokeTierContract() throws Exception {
        Path sample = Path.of("evidence/smoke-examples/sample-summary.json");
        JsonNode node = MAPPER.readTree(sample.toFile());
        assertThat(node.path("evidenceTier").asText()).isEqualTo("SMOKE");
        assertThat(node.path("nonAuthoritative").asBoolean()).isTrue();
        assertThat(node.path("w004Eligible").asBoolean()).isFalse();
        assertThat(node.path("scenario").asText()).isEqualTo("smoke");
        assertThat(node.has("targetRps")).isTrue();
        assertThat(node.has("k6Runner")).isTrue();
    }
}
