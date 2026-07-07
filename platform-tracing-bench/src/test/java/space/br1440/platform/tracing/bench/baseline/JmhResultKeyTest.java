package space.br1440.platform.tracing.bench.baseline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JmhResultKeyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void sameBenchmarkModeParams_match() throws Exception {
        JsonNode entry = readFixture("baseline-entry.json");
        assertThat(JmhResultKey.canonicalKey(entry))
                .isEqualTo("space.example.Benchmark.method|avgt|");
    }

    @Test
    void differentMode_doesNotCollide() throws Exception {
        JsonNode avgt = readFixture("baseline-entry.json");
        ObjectNode sample = avgt.deepCopy();
        sample.put("mode", "sample");

        assertThat(JmhResultKey.canonicalKey(avgt))
                .isNotEqualTo(JmhResultKey.canonicalKey(sample));
    }

    @Test
    void paramsInDifferentJsonOrder_produceSameKey() throws Exception {
        JsonNode first = MAPPER.readTree("""
                {"benchmark":"b","mode":"avgt","params":{"depth":"10","rule":"jwt"}}
                """);
        JsonNode second = MAPPER.readTree("""
                {"benchmark":"b","mode":"avgt","params":{"rule":"jwt","depth":"10"}}
                """);
        assertThat(JmhResultKey.canonicalKey(first)).isEqualTo(JmhResultKey.canonicalKey(second));
        assertThat(JmhResultKey.canonicalKey(first)).isEqualTo("b|avgt|[depth:10, rule:jwt]");
    }

    @Test
    void emptyAndMissingParams_match() {
        assertThat(JmhResultKey.canonicalParams(null)).isEmpty();
        assertThat(JmhResultKey.canonicalParams(MAPPER.createObjectNode())).isEmpty();
    }

    private static JsonNode readFixture(String name) throws Exception {
        try (InputStream input = JmhResultKeyTest.class.getResourceAsStream("/jmh-baseline-fixtures/" + name)) {
            JsonNode root = MAPPER.readTree(input);
            return root.isArray() ? root.get(0) : root;
        }
    }
}
