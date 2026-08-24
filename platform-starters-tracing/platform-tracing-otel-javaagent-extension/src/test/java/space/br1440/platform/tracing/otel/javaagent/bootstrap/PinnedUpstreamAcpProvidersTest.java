package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class PinnedUpstreamAcpProvidersTest {

    private static final String SHA256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void resource_values_are_immutable_and_derived_from_provider_names() {
        // given
        var names = PinnedUpstreamAcpProviders.NAMES;

        // when
        String pinSha256 = PlatformOtelAcpPinHashes.sha256Hex(names);

        // then
        assertThat(names).hasSize(PinnedUpstreamAcpProviders.PIN_COUNT);
        assertThat(pinSha256).isEqualTo(PinnedUpstreamAcpProviders.PIN_SHA256);
        assertThat(PinnedUpstreamAcpProviders.UPSTREAM_AGENT_SHA256).matches("[0-9a-f]{64}");
        assertThatThrownBy(() -> names.add("example.AttackerProvider"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @MethodSource("invalidDocuments")
    void parser_rejects_non_canonical_documents(String document) {
        // given
        byte[] bytes = document.getBytes(StandardCharsets.UTF_8);

        // when
        var result = assertThatThrownBy(() -> PinnedUpstreamAcpProviders.parse(bytes));

        // then
        result.isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("Invalid ACP pin resource:");
    }

    @Test
    void parser_rejects_malformed_utf8() {
        // given
        byte[] malformedUtf8 = {(byte) 0xC3, 0x28};

        // when
        var result = assertThatThrownBy(() -> PinnedUpstreamAcpProviders.parse(malformedUtf8));

        // then
        result.isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("valid UTF-8");
    }

    @Test
    void parser_accepts_canonical_document() {
        // given
        String document = canonicalDocument(
                "provider.0=example.FirstProvider\n"
                        + "provider.1=example.SecondProvider\n");

        // when
        PinnedUpstreamAcpProviders.PinData pin = PinnedUpstreamAcpProviders.parse(
                document.getBytes(StandardCharsets.UTF_8));

        // then
        assertThat(pin.upstreamAgentSha256()).isEqualTo(SHA256);
        assertThat(pin.names()).containsExactly("example.FirstProvider", "example.SecondProvider");
        assertThatThrownBy(() -> pin.names().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static Stream<Arguments> invalidDocuments() {
        return Stream.of(
                Arguments.of(canonicalDocument("provider.0=example.Provider\r\n")),
                Arguments.of(canonicalDocument("provider.1=example.Provider\n")),
                Arguments.of(canonicalDocument(
                        "provider.0=example.SecondProvider\n"
                                + "provider.1=example.FirstProvider\n")),
                Arguments.of(canonicalDocument(
                        "provider.0=example.Provider\n"
                                + "provider.1=example.Provider\n")),
                Arguments.of(canonicalDocument("provider.00=example.Provider\n")),
                Arguments.of(canonicalDocument("provider.0 =example.Provider\n")),
                Arguments.of(canonicalDocument("provider.0=example.Provider\nunknown=value\n")),
                Arguments.of(canonicalDocument("provider.0=example.Provider\n").stripTrailing())
        );
    }

    private static String canonicalDocument(String providerLines) {
        return "format.version=1\n"
                + "upstream.sha256=" + SHA256 + "\n"
                + providerLines;
    }
}
