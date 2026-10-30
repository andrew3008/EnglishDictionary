package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames;

import io.opentelemetry.sdk.autoconfigure.spi.internal.DefaultConfigProperties;

class PlatformServiceNameDefaultsTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("serviceNames")
    void projectsOnlyAnUnspecifiedServiceName(String scenario,
                                              Map<String, String> properties,
                                              Map<String, String> expected) {
        // given
        var config = DefaultConfigProperties.createFromMap(properties);

        // when
        Map<String, String> overrides = BootstrapAutoConfigurationCustomizerProvider.serviceNameDefaults(config);

        // then
        assertThat(overrides).containsExactlyInAnyOrderEntriesOf(expected);
    }

    private static Stream<Arguments> serviceNames() {
        return Stream.of(
                Arguments.of("absent platform name", Map.of(), Map.of()),
                Arguments.of("empty platform name", Map.of(ExtensionPropertyNames.SERVICE_NAME, ""), Map.of()),
                Arguments.of("blank platform name", Map.of(ExtensionPropertyNames.SERVICE_NAME, " \t "), Map.of()),

                Arguments.of("stripped platform name", Map.of(ExtensionPropertyNames.SERVICE_NAME, " demo "),
                        Map.of("otel.service.name", "demo")),

                Arguments.of("explicit service name", Map.of(ExtensionPropertyNames.SERVICE_NAME, "demo",
                        "otel.service.name", "explicit"), Map.of()),

                Arguments.of("empty explicit name", Map.of(ExtensionPropertyNames.SERVICE_NAME, "demo",
                        "otel.service.name", ""), Map.of()),

                Arguments.of("blank explicit name", Map.of(ExtensionPropertyNames.SERVICE_NAME, "demo",
                        "otel.service.name", "   "), Map.of()),

                Arguments.of("resource attribute", Map.of(ExtensionPropertyNames.SERVICE_NAME, "demo",
                        "otel.resource.attributes", "service.name=attribute"), Map.of()),

                Arguments.of("empty resource attribute", Map.of(ExtensionPropertyNames.SERVICE_NAME, "demo",
                        "otel.resource.attributes", "service.name="), Map.of("otel.service.name", "demo"))
        );
    }
}
