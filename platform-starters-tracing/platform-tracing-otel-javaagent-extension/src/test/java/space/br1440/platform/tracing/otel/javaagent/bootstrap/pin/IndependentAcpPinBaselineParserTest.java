package space.br1440.platform.tracing.otel.javaagent.bootstrap.pin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IndependentAcpPinBaselineParserTest {

    @Test
    void reviewed_baseline_parses() throws Exception {
        // given
        Path baseline = Path.of(requiredProperty("platformTracing.acpPinBaseline"));

        // when
        IndependentAcpPinBaselineParser.Document document =
                IndependentAcpPinBaselineParser.parse(Files.readAllBytes(baseline));

        // then
        assertThat(document.pinCount()).isEqualTo(5);
        assertThat(document.providers()).hasSize(5);
        assertThat(document.pinSha256())
                .isEqualTo("25fab90e69b4ceb4efe53b5addb9489de5060b46f4e2577f5c83673b81b9a2ec");
        assertThat(document.upstreamSha256())
                .isEqualTo("9d6bc2ad8dd8fb7f730984988e57b8ac0a82d81c7b3b8ae795378718733a509d");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "duplicate-key",
            "unknown-field",
            "unsorted-providers",
            "pin-count-mismatch",
            "bom",
            "crlf",
            "escaped-string",
            "trailing-content"
    })
    void fail_closed_rejects_invalid_documents(String fixture) {
        // given
        byte[] raw = invalidFixture(fixture);

        // when
        // then
        assertThatThrownBy(() -> IndependentAcpPinBaselineParser.parse(raw))
                .isInstanceOf(IllegalStateException.class);
    }

    private static byte[] invalidFixture(String fixture) {
        return switch (fixture) {
            case "duplicate-key" -> text(validDocument().replace(
                    "\"pinCount\": 5",
                    "\"pinCount\": 5,\n  \"pinCount\": 5"));
            case "unknown-field" -> text(validDocument().replace(
                    "\"pinCount\": 5,",
                    "\"pinCount\": 5,\n  \"extra\": \"no\","));
            case "unsorted-providers" -> text(validDocument()
                    .replace(
                            "io.opentelemetry.contrib.baggage.processor.BaggageProcessorCustomizer",
                            "PLACEHOLDER_LAST")
                    .replace(
                            "io.opentelemetry.javaagent.tooling.config.OtlpProtocolPropertiesSupplier",
                            "io.opentelemetry.contrib.baggage.processor.BaggageProcessorCustomizer")
                    .replace(
                            "PLACEHOLDER_LAST",
                            "io.opentelemetry.javaagent.tooling.config.OtlpProtocolPropertiesSupplier"));
            case "pin-count-mismatch" -> text(validDocument().replace("\"pinCount\": 5", "\"pinCount\": 4"));
            case "bom" -> {
                byte[] json = text(validDocument());
                byte[] bom = new byte[json.length + 3];
                bom[0] = (byte) 0xEF;
                bom[1] = (byte) 0xBB;
                bom[2] = (byte) 0xBF;
                System.arraycopy(json, 0, bom, 3, json.length);
                yield bom;
            }
            case "crlf" -> text(validDocument().replace("\n", "\r\n"));
            case "escaped-string" -> text(validDocument().replace(
                    "\"coordinate\": \"io.opentelemetry.javaagent:opentelemetry-javaagent:2.30.0\"",
                    "\"coordinate\": \"io.opentelemetry.javaagent:\\\"opentelemetry-javaagent\\\":2.30.0\""));
            case "trailing-content" -> text(validDocument() + "\n{}");
            default -> throw new IllegalArgumentException(fixture);
        };
    }

    private static byte[] text(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String validDocument() {
        return """
                {
                  "formatVersion": 1,
                  "coordinate": "io.opentelemetry.javaagent:opentelemetry-javaagent:2.30.0",
                  "upstreamSha256": "9d6bc2ad8dd8fb7f730984988e57b8ac0a82d81c7b3b8ae795378718733a509d",
                  "upstreamChecksumSource": {
                    "type": "PUBLISHED_RELEASE_CHECKSUM",
                    "reference": "https://example.test/checksum"
                  },
                  "pinSha256": "25fab90e69b4ceb4efe53b5addb9489de5060b46f4e2577f5c83673b81b9a2ec",
                  "pinCount": 5,
                  "providers": [
                    {
                      "binaryName": "io.opentelemetry.contrib.baggage.processor.BaggageProcessorCustomizer",
                      "containerEntry": "inst",
                      "descriptorPath": "inst/META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider"
                    },
                    {
                      "binaryName": "io.opentelemetry.javaagent.shaded.instrumentation.resources.internal.ResourceProviderPropertiesCustomizer",
                      "containerEntry": "inst",
                      "descriptorPath": "inst/META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider"
                    },
                    {
                      "binaryName": "io.opentelemetry.javaagent.tooling.AgentTracerProviderConfigurer",
                      "containerEntry": "inst",
                      "descriptorPath": "inst/META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider"
                    },
                    {
                      "binaryName": "io.opentelemetry.javaagent.tooling.config.ConfigurationPropertiesSupplier",
                      "containerEntry": "inst",
                      "descriptorPath": "inst/META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider"
                    },
                    {
                      "binaryName": "io.opentelemetry.javaagent.tooling.config.OtlpProtocolPropertiesSupplier",
                      "containerEntry": "inst",
                      "descriptorPath": "inst/META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider"
                    }
                  ],
                  "loaderContract": {
                    "repository": "https://github.com/open-telemetry/opentelemetry-java-instrumentation",
                    "tag": "v2.30.0",
                    "commit": "080c1d32ca676ba7b0210c21b60cce8e7ac39058",
                    "sources": [
                      {
                        "path": "javaagent-bootstrap/src/main/java/io/opentelemetry/javaagent/bootstrap/AgentClassLoader.java",
                        "sha256": "772de8ef40e6c13d07e477352a7d2c178936b24370a71368ec281f1782769b1d"
                      }
                    ],
                    "conclusionSha256": "bd30818b975a3459e4b396fc82fcd66a8f42a833a10456efa663c79a907074d4"
                  }
                }
                """;
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required test system property is missing: " + name);
        }
        return value;
    }
}
