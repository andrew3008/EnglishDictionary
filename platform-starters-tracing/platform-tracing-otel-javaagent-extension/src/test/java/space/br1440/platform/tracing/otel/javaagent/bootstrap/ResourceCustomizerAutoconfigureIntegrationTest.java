package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.otel.javaagent.AutoConfiguredSdkIsolationExtension;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdkBuilder;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.resources.internal.EntityUtil;

@ExtendWith(AutoConfiguredSdkIsolationExtension.class)
@ResourceLock(AgentLogCapture.RESOURCE_LOCK)
@ResourceLock("otel-resource-jul")
class ResourceCustomizerAutoconfigureIntegrationTest {

    private static final AttributeKey<String> SERVICE_NAME = AttributeKey.stringKey(PlatformAttributes.SERVICE_NAME);
    private static final AttributeKey<String> ENVIRONMENT = AttributeKey.stringKey(PlatformAttributes.PLATFORM_ENVIRONMENT);
    private static final AttributeKey<String> HOST_NAME = AttributeKey.stringKey(PlatformAttributes.PLATFORM_HOST);
    private static final AttributeKey<String> CONTAINER_ID = AttributeKey.stringKey(PlatformAttributes.CONTAINER_ID);
    private static final AttributeKey<String> DETECTOR_MARKER = AttributeKey.stringKey("test.detector.marker");

    @Test
    void platform_name_creates_service_entity_without_resource_warnings() {
        // given
        Map<String, String> properties = Map.of(
                ExtensionPropertyNames.SERVICE_NAME, " demo ",
                ExtensionPropertyNames.SERVICE_ENVIRONMENT, "production"
        );
        try (ResourceLogCapture logs = new ResourceLogCapture()) {
            // when
            Resource resource = configure(properties, builder -> {
            });

            // then
            assertThat(resource.getAttribute(SERVICE_NAME)).isEqualTo("demo");
            assertThat(EntityUtil.getEntities(resource))
                    .filteredOn(entity -> entity.getType().equals("service"))
                    .singleElement()
                    .satisfies(entity -> assertThat(entity.getId().get(SERVICE_NAME)).isEqualTo("demo"));
            assertThat(logs.records).noneMatch(record -> record.getLevel().intValue() >= Level.WARNING.intValue());
        }
    }

    @Test
    void resource_service_name_and_instance_id_win_over_platform_defaults() {
        // given
        Map<String, String> properties = Map.of(
                "otel.resource.attributes", "service.name=attribute-service,service.instance.id=x",
                ExtensionPropertyNames.SERVICE_NAME, "platform-service",
                ExtensionPropertyNames.SERVICE_ENVIRONMENT, "production"
        );

        // when
        Resource resource = configure(properties, builder -> {
        });

        // then
        assertThat(resource.getAttribute(SERVICE_NAME)).isEqualTo("attribute-service");
        assertThat(resource.getAttribute(AttributeKey.stringKey("service.instance.id"))).isEqualTo("x");
    }

    @Test
    void disabled_service_name_is_not_restored_and_warning_reports_missing_identity() {
        // given
        Map<String, String> properties = disabledServiceName("WARNING");
        try (AgentLogCapture logs = AgentLogCapture.installed()) {
            // when
            Resource resource = configure(properties, builder -> {
            });

            // then
            assertThat(resource.getAttribute(SERVICE_NAME)).isNull();
            assertThat(logs.events())
                    .filteredOn(event -> event.eventName().equals("RESOURCE_IDENTITY_INCOMPLETE"))
                    .singleElement()
                    .satisfies(event -> {
                        assertThat(event.level()).isEqualTo(AgentLogLevel.WARN);
                        assertThat(event.fields()).containsEntry("missingKeys", "service.name");
                    });
        }
    }

    @Test
    void disabled_service_name_fails_error_validation() {
        // given
        Map<String, String> properties = disabledServiceName("ERROR");

        // when
        Throwable failure = catchThrowable(() -> configure(properties, builder -> {
        }));

        // then
        assertThat(failure).hasStackTraceContaining("missing required keys [service.name]");
    }

    @Test
    void pinned_resource_detectors_preserve_service_schema_without_merge_conflicts() throws Exception {
        // given
        URL detectors = ResourceCustomizerAutoconfigureIntegrationTest.class
                .getResource("/resource-detectors/opentelemetry-resources.jar");
        assertThat(detectors).as("resource detectors fixture").isNotNull();
        Map<String, String> properties = Map.of(
                ExtensionPropertyNames.SERVICE_NAME, "platform-service",
                ExtensionPropertyNames.SERVICE_ENVIRONMENT, "production",
                "otel.java.enabled.resource.providers", String.join(",",
                        "io.opentelemetry.sdk.autoconfigure.EnvironmentResourceProvider",
                        "io.opentelemetry.sdk.autoconfigure.ServiceInstanceIdResourceProvider",
                        "io.opentelemetry.instrumentation.resources.HostResourceProvider",
                        "io.opentelemetry.instrumentation.resources.OsResourceProvider",
                        "io.opentelemetry.instrumentation.resources.ProcessResourceProvider",
                        "io.opentelemetry.instrumentation.resources.ProcessRuntimeResourceProvider")
        );
        try (URLClassLoader loader = new URLClassLoader(new URL[]{detectors},
                AutoConfigurationCustomizerTestClassLoader.withoutDiscoveredCustomizers());
             ResourceLogCapture logs = new ResourceLogCapture()) {
            // when
            Resource resource = configure(properties, builder -> builder.setServiceClassLoader(loader));

            // then
            for (String key : List.of("host.arch", "os.description", "process.executable.path", "process.runtime.name")) {
                assertThat(resource.getAttribute(AttributeKey.stringKey(key))).as(key).isNotBlank();
            }
            assertThat(resource.getAttribute(SERVICE_NAME)).isEqualTo("platform-service");
            assertThat(resource.getSchemaUrl()).isEqualTo("https://opentelemetry.io/schemas/1.40.0");
            assertThat(logs.records).noneMatch(record -> record.getMessage()
                    .contains("Attempting to merge Resources with different schemaUrls"));
        }
    }

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
        try (BootstrapCoordinator coordinator = RuntimeEvidenceTestFixtures.coordinator()) {
            new BootstrapAutoConfigurationCustomizerProvider(coordinator).customize(builder);
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

    private static Map<String, String> disabledServiceName(String validationMode) {
        return Map.of(
                "otel.resource.disabled.keys", "service.name",
                ExtensionPropertyNames.SERVICE_NAME, "platform-service",
                ExtensionPropertyNames.SERVICE_ENVIRONMENT, "production",
                ExtensionPropertyNames.RESOURCE_VALIDATION_MODE, validationMode
        );
    }

    private static final class ResourceLogCapture extends Handler implements AutoCloseable {

        private final Logger logger = Logger.getLogger("io.opentelemetry.sdk.resources");
        private final Level previousLevel = logger.getLevel();
        private final List<LogRecord> records = new ArrayList<>();

        private ResourceLogCapture() {
            logger.setLevel(Level.INFO);
            logger.addHandler(this);
        }

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
            logger.removeHandler(this);
            logger.setLevel(previousLevel);
        }
    }
}
