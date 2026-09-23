package space.br1440.platform.tracing.otel.javaagent.logging;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

class OwnerMaskingVocabularyTest {

    @Test
    void canonical_owner_keys_are_a_subset_of_agent_sensitive_keys() throws IOException {
        // given
        Set<String> ownerCanonical = new LinkedHashSet<>();
        for (String key : readApplyToKeys()) {
            ownerCanonical.add(AgentLogFieldNamePolicy.canonicalName(key));
        }

        // when
        Set<String> agentCanonical = AgentLogFieldNamePolicy.canonicalSensitiveKeys();

        // then
        assertThat(agentCanonical).containsAll(ownerCanonical);
    }

    @ParameterizedTest
    @MethodSource("ownerApplyToKeys")
    void logger_rejects_each_owner_apply_to_key(String key) {
        // given
        AgentLogCapture capture = new AgentLogCapture();
        capture.install();
        try {
            PlatformAgentLogger logger = PlatformAgentLogger.get(OwnerMaskingVocabularyTest.class);

            // when
            EmitResult result = logger.warn(AgentEventCode.CTRL_UPDATE, "Tracing settings update failed",
                    key, "value");

            // then
            assertThat(result).isEqualTo(EmitResult.REJECTED_BY_SECURITY_POLICY);
            assertThat(capture.events()).isEmpty();
        } finally {
            capture.restore();
        }
    }

    static Set<String> ownerApplyToKeys() throws IOException {
        return readApplyToKeys();
    }

    private static Set<String> readApplyToKeys() throws IOException {
        try (InputStream input = OwnerMaskingVocabularyTest.class
                .getResourceAsStream("/platform-log-masking-rules.yaml")) {
            assertThat(input).as("owner masking YAML must be on the test classpath").isNotNull();
            String yaml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            Set<String> keys = new LinkedHashSet<>();
            boolean inApplyToKeys = false;
            for (String line : yaml.split("\n", -1)) {
                String trimmed = line.stripTrailing();
                if (trimmed.endsWith("apply-to-keys:")) {
                    inApplyToKeys = true;
                    continue;
                }
                if (inApplyToKeys) {
                    String item = trimmed.strip();
                    if (item.startsWith("- ")) {
                        keys.add(item.substring(2).trim());
                        continue;
                    }
                    if (!item.isEmpty() && !item.startsWith("#")) {
                        inApplyToKeys = false;
                    }
                }
            }
            return Set.copyOf(keys);
        }
    }
}
