package space.br1440.platform.tracing.otel.javaagent.bootstrap.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class UpstreamCustomizerProviderAllowlistTest {

    private static final String SHA256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String INVALID_PIN = "Invalid upstream customizer provider pin ";

    @Test
    void packaged_allowlist_exposes_sha256_and_immutable_names() throws IOException {
        // given
        String document;
        try (InputStream input = UpstreamCustomizerProviderAllowlist.class.getResourceAsStream(
                UpstreamCustomizerProviderAllowlist.RESOURCE_PATH
        )) {
            assertThat(input).isNotNull();
            document = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        UpstreamCustomizerProviderAllowlist.Pin pin = UpstreamCustomizerProviderAllowlist.parse(
                document.getBytes(StandardCharsets.UTF_8)
        );

        // when
        Set<String> names = pin.providerClassNames();

        // then
        assertThat(pinKeys(document)).containsExactly(
                "format.version",
                "otel.javaagent.sha256",
                "provider.0",
                "provider.1",
                "provider.2",
                "provider.3",
                "provider.4",
                "provider.5",
                "provider.6"
        );
        assertThat(pin.otelJavaAgentSha256()).matches("[0-9a-f]{64}");
        assertThat(names).containsExactly(
                "io.opentelemetry.contrib.baggage.processor.BaggageProcessorCustomizer",
                "io.opentelemetry.javaagent.shaded.instrumentation.exporter.internal.ZipkinExporterRemovalCustomizerProvider",
                "io.opentelemetry.javaagent.shaded.instrumentation.resources.internal.ResourceProviderPropertiesCustomizer",
                "io.opentelemetry.javaagent.shaded.instrumentation.sampler.internal.LinksBasedSamplerDeprecationCustomizerProvider",
                "io.opentelemetry.javaagent.tooling.AgentTracerProviderConfigurer",
                "io.opentelemetry.javaagent.tooling.config.ConfigurationPropertiesSupplier",
                "io.opentelemetry.javaagent.tooling.config.OtlpProtocolPropertiesSupplier"
        );
        assertThatThrownBy(() -> names.add("example.AttackerProvider"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @MethodSource("invalidDocuments")
    void parser_rejects_non_canonical_documents(String document) {
        // given
        byte[] bytes = document.getBytes(StandardCharsets.UTF_8);

        // when
        // then
        assertThatThrownBy(() -> UpstreamCustomizerProviderAllowlist.parse(bytes))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith(INVALID_PIN);
    }

    @Test
    void parser_accepts_canonical_document() {
        // given
        String document = canonicalDocument("""
                provider.0=example.FirstProvider
                provider.1=example.SecondProvider
                """);

        // when
        UpstreamCustomizerProviderAllowlist.Pin pin = UpstreamCustomizerProviderAllowlist.parse(
                document.getBytes(StandardCharsets.UTF_8));

        // then
        assertThat(pin.otelJavaAgentSha256()).isEqualTo(SHA256);
        assertThat(pin.providerClassNames()).containsExactly("example.FirstProvider", "example.SecondProvider");
        assertThatThrownBy(pin.providerClassNames()::clear).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void parser_rejects_malformed_utf8() {
        // given
        byte[] malformedUtf8 = {(byte) 0xC3, 0x28};

        // when
        // then
        assertThatThrownBy(() -> UpstreamCustomizerProviderAllowlist.parse(malformedUtf8))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("valid UTF-8");
    }

    @Test
    void load_rejects_a_resource_above_the_byte_limit() {
        // given
        byte[] oversized = new byte[16 * 1024 + 1];

        // when
        // then
        assertThatThrownBy(() -> UpstreamCustomizerProviderAllowlist.load(new ByteArrayInputStream(oversized)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exceeds");
    }

    private static Stream<Arguments> invalidDocuments() {
        return Stream.of(
                Arguments.of(canonicalDocument("provider.0=example.Provider\r\n")),
                Arguments.of("\uFEFF" + canonicalDocument("provider.0=example.Provider\n")),
                Arguments.of(canonicalDocument("provider.0=example.Provider\n\n")),
                Arguments.of(canonicalDocument("provider.0=\n")),
                Arguments.of(canonicalDocument("=example.Provider\n")),
                Arguments.of(canonicalDocument("provider.0=example.Provider=extra\n")),
                Arguments.of(canonicalDocument("provider.0 =example.Provider\n")),
                Arguments.of(canonicalDocument(" provider.0=example.Provider\n")),
                Arguments.of(canonicalDocument("format.version=1\nprovider.0=example.Provider\n")),
                Arguments.of(canonicalDocument("provider.0=example.Provider\nunknown=value\n")),
                Arguments.of(canonicalDocument("provider.01=example.Provider\n")),
                Arguments.of(canonicalDocument("provider.-1=example.Provider\n")),
                Arguments.of(canonicalDocument("provider.x=example.Provider\n")),
                Arguments.of(canonicalDocument("provider.1234567890=example.Provider\n")),
                Arguments.of(canonicalDocument("""
                        provider.0=example.Provider
                        provider.2=example.SecondProvider
                        """)),
                Arguments.of(canonicalDocument("provider.1=example.Provider\n")),
                Arguments.of(canonicalDocument("provider.0=1abc\n")),
                Arguments.of(canonicalDocument("provider.0=a..b\n")),
                Arguments.of(canonicalDocument("""
                        provider.0=example.Provider
                        provider.1=example.Provider
                        """)),
                Arguments.of(canonicalDocument("""
                        provider.0=example.SecondProvider
                        provider.1=example.FirstProvider
                        """)),
                Arguments.of(canonicalDocument("provider.0=example.Provider\n").replace("format.version=1", "format.version=2")),
                Arguments.of(canonicalDocument("provider.0=example.Provider\n")
                        .replace(SHA256, SHA256.toUpperCase(Locale.ROOT))),
                Arguments.of(canonicalDocument("provider.0=example.Provider\n").replace(SHA256, "a".repeat(63))),
                Arguments.of(canonicalDocument("")),
                Arguments.of(canonicalDocument("provider.0=example.Provider\n").stripTrailing())
        );
    }

    private static List<String> pinKeys(String document) {
        return document.lines()
                .map(line -> line.substring(0, line.indexOf('=')))
                .toList();
    }

    private static String canonicalDocument(String providerLines) {
        return "format.version=1\n"
                + "otel.javaagent.sha256=" + SHA256 + "\n"
                + providerLines;
    }

}
