package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformOtelAcpPinHashesTest {

    @Test
    void sha256_is_independent_of_iteration_order() {
        // given
        List<String> names = List.of(
                "io.opentelemetry.javaagent.tooling.AgentTracerProviderConfigurer",
                "io.opentelemetry.contrib.baggage.processor.BaggageProcessorCustomizer",
                "io.opentelemetry.javaagent.tooling.config.OtlpProtocolPropertiesSupplier",
                "io.opentelemetry.javaagent.shaded.instrumentation.resources.internal."
                        + "ResourceProviderPropertiesCustomizer",
                "io.opentelemetry.javaagent.tooling.config.ConfigurationPropertiesSupplier"
        );
        Set<String> forward = new LinkedHashSet<>(names);
        Set<String> reverse = new LinkedHashSet<>();
        for (int i = names.size() - 1; i >= 0; i--) {
            reverse.add(names.get(i));
        }

        // when
        String forwardHash = PlatformOtelAcpPinHashes.sha256Hex(forward);
        String reverseHash = PlatformOtelAcpPinHashes.sha256Hex(reverse);

        // then
        assertThat(forwardHash)
                .isEqualTo(reverseHash)
                .isEqualTo("25fab90e69b4ceb4efe53b5addb9489de5060b46f4e2577f5c83673b81b9a2ec");
    }

    @Test
    void empty_set_hashes_empty_preimage() {
        // given
        Set<String> names = Set.of();

        // when
        String hash = PlatformOtelAcpPinHashes.sha256Hex(names);

        // then
        assertThat(hash).isEqualTo(PlatformOtelAcpPinHashes.sha256Hex(new byte[0]));
    }
}
