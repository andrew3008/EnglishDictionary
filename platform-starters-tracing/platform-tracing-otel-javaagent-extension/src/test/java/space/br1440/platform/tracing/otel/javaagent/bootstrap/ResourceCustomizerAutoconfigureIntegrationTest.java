package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.otel.javaagent.AutoConfiguredSdkIsolationExtension;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdkBuilder;
import io.opentelemetry.sdk.resources.Resource;

@ExtendWith(AutoConfiguredSdkIsolationExtension.class)
class ResourceCustomizerAutoconfigureIntegrationTest {

    private static final AttributeKey<String> SERVICE_NAME = AttributeKey.stringKey(PlatformAttributes.SERVICE_NAME);
    private static final AttributeKey<String> ENVIRONMENT = AttributeKey.stringKey(PlatformAttributes.PLATFORM_ENVIRONMENT);
    private static final AttributeKey<String> HOST_NAME = AttributeKey.stringKey(PlatformAttributes.PLATFORM_HOST);
    private static final AttributeKey<String> CONTAINER_ID = AttributeKey.stringKey(PlatformAttributes.CONTAINER_ID);
    private static final AttributeKey<String> DETECTOR_MARKER = AttributeKey.stringKey("test.detector.marker");

    @Test
    void explicit_otel_resource_wins_over_platform_fallback() {
        // given
        Map<String, String> properties = Map.of(
                "otel.service.name", "otel-service",
                "otel.resource.attributes",
                "deployment.environment.name=staging,host.name=otel-host,container.id=otel-container",
                ExtensionPropertyNames.SERVICE_NAME, "platform-service",
                ExtensionPropertyNames.SERVICE_ENVIRONMENT, "production",
                ExtensionPropertyNames.SERVICE_HOST, "platform-host",
                ExtensionPropertyNames.SERVICE_CONTAINER_ID, "platform-container");

        // when
        Resource resource = configure(properties, builder -> {
        });

        // then
        assertThat(resource.getAttribute(SERVICE_NAME)).isEqualTo("otel-service");
        assertThat(resource.getAttribute(ENVIRONMENT)).isEqualTo("staging");
        assertThat(resource.getAttribute(HOST_NAME)).isEqualTo("otel-host");
        assertThat(resource.getAttribute(CONTAINER_ID)).isEqualTo("otel-container");
    }

    @Test
    void platform_fills_only_missing_keys_and_preserves_detector_attributes() {
        // given
        Map<String, String> properties = Map.of(
                ExtensionPropertyNames.SERVICE_NAME, "platform-service",
                ExtensionPropertyNames.SERVICE_ENVIRONMENT, "production"
        );

        // when
        Resource resource = configure(properties, builder -> builder.addResourceCustomizer((existing, config) ->
                existing.merge(Resource.create(Attributes.of(DETECTOR_MARKER, "present")))));

        // then
        assertThat(resource.getAttribute(SERVICE_NAME)).isEqualTo("platform-service");
        assertThat(resource.getAttribute(ENVIRONMENT)).isEqualTo("production");
        assertThat(resource.getAttribute(DETECTOR_MARKER)).isEqualTo("present");
    }

    @Test
    void disabled_keys_are_not_returned_by_platform_customizer() {
        // given
        Map<String, String> properties = Map.of(
                "otel.resource.disabled.keys", PlatformAttributes.PLATFORM_HOST,
                ExtensionPropertyNames.SERVICE_NAME, "platform-service",
                ExtensionPropertyNames.SERVICE_ENVIRONMENT, "production",
                ExtensionPropertyNames.SERVICE_HOST, "platform-host"
        );

        // when
        Resource resource = configure(properties, builder -> {
        });

        // then
        assertThat(resource.getAttribute(SERVICE_NAME)).isEqualTo("platform-service");
        assertThat(resource.getAttribute(HOST_NAME)).isNull();
    }

    private static Resource configure(Map<String, String> properties,
                                      Consumer<AutoConfiguredOpenTelemetrySdkBuilder> beforePlatform) {
        Map<String, String> effective = new HashMap<>(properties);
        effective.put("otel.metrics.exporter", "none");
        effective.put("otel.logs.exporter", "none");
        effective.put("otel.traces.exporter", "none");

        AtomicReference<Resource> observed = new AtomicReference<>();
        AutoConfiguredOpenTelemetrySdkBuilder builder = AutoConfiguredOpenTelemetrySdk.builder()
                .setServiceClassLoader(
                        AutoConfigurationCustomizerTestClassLoader.withoutDiscoveredCustomizers()
                )
                .disableShutdownHook()
                .addPropertiesSupplier(() -> Map.copyOf(effective));
        beforePlatform.accept(builder);
        new BootstrapAutoConfigurationCustomizerProvider(RuntimeEvidenceTestFixtures.coordinator()).customize(builder);
        builder.addResourceCustomizer((resource, config) -> {
            observed.set(resource);
            return resource;
        });

        AutoConfiguredOpenTelemetrySdk sdk = builder.build();
        try {
            assertThat(sdk.getOpenTelemetrySdk()).isNotNull();
            return observed.get();
        } finally {
            sdk.getOpenTelemetrySdk().close();
        }
    }
}
