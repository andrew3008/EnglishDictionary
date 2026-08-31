package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class AllowedUpstreamAcpProvidersTest {

    private static final String SHA256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void packaged_allowlist_is_non_empty_and_immutable() {
        // given
        Set<String> names = AllowedUpstreamAcpProviders.NAMES;

        // when
        var mutation = assertThatThrownBy(() -> names.add("example.AttackerProvider"));

        // then
        assertThat(names).isNotEmpty();
        mutation.isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @MethodSource("invalidDocuments")
    void parser_rejects_non_canonical_documents(String document) {
        // given
        byte[] bytes = document.getBytes(StandardCharsets.UTF_8);

        // when
        var result = assertThatThrownBy(() -> AllowedUpstreamAcpProviders.parse(bytes));

        // then
        result.isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("Invalid ACP pin resource:");
    }

    @Test
    void parser_rejects_malformed_utf8() {
        // given
        byte[] malformedUtf8 = {(byte) 0xC3, 0x28};

        // when
        var result = assertThatThrownBy(() -> AllowedUpstreamAcpProviders.parse(malformedUtf8));

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
        Set<String> names = AllowedUpstreamAcpProviders.parse(document.getBytes(StandardCharsets.UTF_8));

        // then
        assertThat(names).containsExactly("example.FirstProvider", "example.SecondProvider");
        assertThatThrownBy(names::clear).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void parser_requires_a_well_formed_reviewed_upstream_digest() {
        // given
        String document = "format.version=1\n"
                + "otel.javaagent.sha256=NOT-A-DIGEST\n"
                + "provider.0=example.Provider\n";

        // when
        var result = assertThatThrownBy(
                () -> AllowedUpstreamAcpProviders.parse(document.getBytes(StandardCharsets.UTF_8)));

        // then
        result.isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("otel.javaagent.sha256");
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
                + "otel.javaagent.sha256=" + SHA256 + "\n"
                + providerLines;
    }
}
