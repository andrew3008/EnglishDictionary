package space.br1440.platform.tracing.otel.javaagent.bootstrap.pin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

class IndependentUpstreamAcpJarExtractorTest {

    @Test
    void extracted_names_equal_reviewed_baseline() throws Exception {
        // given
        Path agentJar = Path.of(requiredProperty("platformTracing.upstreamAgentJar"));
        Path baseline = Path.of(requiredProperty("platformTracing.acpPinBaseline"));

        // when
        List<String> extracted = IndependentUpstreamAcpJarExtractor.extractSortedNames(agentJar);
        IndependentAcpPinBaselineParser.Document document =
                IndependentAcpPinBaselineParser.parse(Files.readAllBytes(baseline));
        String fileSha = IndependentUpstreamAcpJarExtractor.sha256Hex(Files.readAllBytes(agentJar));

        // then
        assertThat(extracted).isEqualTo(document.providers());
        assertThat(fileSha).isEqualTo(document.upstreamSha256());
    }

    @Test
    void parser_rejects_bom_duplicate_and_platform_provider() {
        // given
        byte[] bom = new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'a', '.', 'B'};
        byte[] duplicate = "a.A\na.A\n".getBytes(StandardCharsets.UTF_8);
        byte[] platform = (IndependentUpstreamAcpJarExtractor.PLATFORM_PROVIDER + "\n")
                .getBytes(StandardCharsets.UTF_8);

        // when
        // then
        assertThatThrownBy(() -> IndependentUpstreamAcpJarExtractor.parseDescriptor(bom))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> IndependentUpstreamAcpJarExtractor.parseDescriptor(duplicate))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> IndependentUpstreamAcpJarExtractor.parseDescriptor(platform))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void parser_accepts_comments_and_whitespace() {
        // given
        byte[] descriptor = """
                # header
                io.opentelemetry.javaagent.tooling.AgentTracerProviderConfigurer
                  io.opentelemetry.contrib.baggage.processor.BaggageProcessorCustomizer
                """.getBytes(StandardCharsets.UTF_8);

        // when
        List<String> names = IndependentUpstreamAcpJarExtractor.parseDescriptor(descriptor);

        // then
        assertThat(names).containsExactly(
                "io.opentelemetry.contrib.baggage.processor.BaggageProcessorCustomizer",
                "io.opentelemetry.javaagent.tooling.AgentTracerProviderConfigurer");
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required test system property is missing: " + name);
        }
        return value;
    }
}
