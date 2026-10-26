package space.br1440.platform.tracing.otel.javaagent.distribution;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.jar.Manifest;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import space.br1440.platform.common.util.StringUtils;

class ExtensionSurfaceTest {

    private static final String MANIFEST_ENTRY = "META-INF/MANIFEST.MF";
    private static final String ACP_PIN_RESOURCE = "META-INF/platform-tracing/otel-javaagent-acp-pin.properties";
    private static final String SCHEMA_IDENTITY = "META-INF/platform-tracing/agent-logging-schema.properties";
    private static final String EXTENSION_BUILD_METADATA =
            "space/br1440/platform/tracing/otel/javaagent/readiness/platform-extension-build.properties";
    private static final String LOG_EVENT_SCHEMA_RESOURCE = "platform-log-event.schema.json";
    private static final String SAMPLER_PROVIDER_DESCRIPTOR =
            "META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.traces.ConfigurableSamplerProvider";
    private static final String ACP_PIN_PROVIDERS_CLASS =
            "space/br1440/platform/tracing/otel/javaagent/bootstrap/verification/UpstreamCustomizerProviderAllowlist.class";
    private static final String EXTENSION_IMPLEMENTATION_TITLE = "platform-tracing-otel-javaagent-extension";
    private static final Map<String, String> EXPECTED_SERVICE_DESCRIPTORS = Map.of(
            "META-INF/services/io.opentelemetry.javaagent.extension.AgentListener",
            "space.br1440.platform.tracing.otel.javaagent.bootstrap.ActivationAgentListener",
            "META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider",
            "space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapAutoConfigurationCustomizerProvider",
            "META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.ConfigurablePropagatorProvider",
            "space.br1440.platform.tracing.otel.javaagent.propagation.TraceControlPropagatorProvider",
            "META-INF/services/io.opentelemetry.sdk.common.export.GrpcSenderProvider",
            "space.br1440.platform.tracing.otel.javaagent.exporter.response.ObservingGrpcSenderProvider",
            "META-INF/services/io.opentelemetry.sdk.common.export.HttpSenderProvider",
            "space.br1440.platform.tracing.otel.javaagent.exporter.response.ObservingHttpSenderProvider"
    );
    private static final List<String> FORBIDDEN_EXTENSION_PREFIXES = List.of(
            "org/springframework/",
            "org/slf4j/",
            "ch/qos/logback/",
            "io/opentelemetry/api/",
            "io/opentelemetry/context/",
            "io/opentelemetry/common/",
            "space/br1440/platform/logging/",
            "space/br1440/platform/tracing/otel/runtime/",
            "space/br1440/platform/tracing/autoconfigure/"
    );

    @Test
    void packaged_extension_stays_on_the_allowlisted_surface() throws IOException {
        // given
        Path extension = AgentArchive.requiredPath("platformTracing.extensionJar");
        Map<String, byte[]> entries = AgentArchive.readEntries(extension);
        String version = AgentArchive.requiredProperty("platformTracing.distributionVersion");
        Manifest manifest = new Manifest(new ByteArrayInputStream(entries.get(MANIFEST_ENTRY)));

        // when
        Properties schema = AgentArchive.loadProperties(entries.get(SCHEMA_IDENTITY));
        Properties buildMetadata = AgentArchive.loadProperties(entries.get(EXTENSION_BUILD_METADATA));

        // then
        assertExtensionSurfaceIsAllowlisted(entries.keySet());
        assertServiceDescriptors(entries);
        assertExtensionManifestMatches(manifest, version, schema);
        assertThat(buildMetadata)
                .containsEntry("format.version", "1")
                .containsEntry("artifact", EXTENSION_IMPLEMENTATION_TITLE)
                .containsEntry("version", version);
    }

    private static void assertExtensionSurfaceIsAllowlisted(Set<String> entryNames) {
        assertThat(entryNames)
                .noneMatch(name -> FORBIDDEN_EXTENSION_PREFIXES.stream().anyMatch(name::startsWith))
                .contains(
                        ACP_PIN_PROVIDERS_CLASS,
                        ACP_PIN_RESOURCE,
                        SCHEMA_IDENTITY,
                        EXTENSION_BUILD_METADATA
                )
                .anyMatch(name -> name.startsWith("jakarta/annotation/") && name.endsWith(".class"))
                .anyMatch(name -> name.startsWith("space/br1440/platform/tracing/otel/javaagent/shaded/com/google/protobuf/"))
                .anyMatch(name -> name.startsWith("space/br1440/platform/tracing/otel/javaagent/shaded/io/opentelemetry/proto/"))
                .doesNotContain(LOG_EVENT_SCHEMA_RESOURCE);
    }

    private static void assertServiceDescriptors(Map<String, byte[]> entries) {
        Set<String> actualDescriptors = entries.keySet().stream()
                .filter(name -> name.startsWith("META-INF/services/"))
                .filter(name -> !"META-INF/services/".equals(name))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        assertThat(actualDescriptors)
                .doesNotContain(SAMPLER_PROVIDER_DESCRIPTOR)
                .containsExactlyInAnyOrderElementsOf(EXPECTED_SERVICE_DESCRIPTORS.keySet());
        EXPECTED_SERVICE_DESCRIPTORS.forEach((path, expectedProvider) -> {
            List<String> providerLines = new String(entries.get(path), StandardCharsets.UTF_8)
                    .lines()
                    .filter(StringUtils::hasText)
                    .toList();
            assertThat(providerLines)
                    .as(path)
                    .containsExactly(expectedProvider);
        });
    }

    private static void assertExtensionManifestMatches(Manifest manifest, String version, Properties schema) {
        assertThat(manifest.getMainAttributes().getValue("Implementation-Title"))
                .isEqualTo(EXTENSION_IMPLEMENTATION_TITLE);
        assertThat(manifest.getMainAttributes().getValue("Implementation-Version"))
                .isEqualTo(version);
        assertThat(manifest.getMainAttributes().getValue("Platform-Log-Schema-SHA256"))
                .isEqualTo(schema.getProperty("schema.sha256"));
        assertThat(manifest.getMainAttributes().getValue("Platform-Log-Schema-Version"))
                .isEqualTo(schema.getProperty("schema.version"));
    }
}
