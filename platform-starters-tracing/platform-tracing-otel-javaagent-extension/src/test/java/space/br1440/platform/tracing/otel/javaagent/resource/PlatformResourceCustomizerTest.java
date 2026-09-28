package space.br1440.platform.tracing.otel.javaagent.resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames;
import space.br1440.platform.tracing.otel.javaagent.diagnostics.TracingDiagnostics;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;
import space.br1440.platform.tracing.otel.javaagent.logging.CapturedLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.resources.Resource;

@ResourceLock(AgentLogCapture.RESOURCE_LOCK)
class PlatformResourceCustomizerTest {

    private static final Function<String, String> NO_ENV = name -> null;
    private static final Supplier<Optional<String>> NO_BUILD = Optional::empty;

    private static AttributeKey<String> key(String name) {
        return AttributeKey.stringKey(name);
    }

    private static PlatformResourceCustomizer customizer(Function<String, String> env,
                                                         Supplier<Optional<String>> buildVersion,
                                                         HostNameResolver host,
                                                         TracingDiagnostics diagnostics) {
        return new PlatformResourceCustomizer(host, env, buildVersion, diagnostics);
    }

    private static PlatformResourceCustomizer customizer(Function<String, String> env,
                                                         Supplier<Optional<String>> buildVersion,
                                                         HostNameResolver host) {
        return customizer(env, buildVersion, host, new TracingDiagnostics());
    }

    private static HostNameResolver noHost() {
        return new HostNameResolver(NO_ENV, () -> null);
    }

    @Test
    void platformPropertiesFillIdentity() {
        // given
        Map<String, String> values = Map.of(
                PlatformResourceCustomizer.PROP_SERVICE_NAME, "demo-service",
                PlatformResourceCustomizer.PROP_SERVICE_VERSION, "1.0.0",
                PlatformResourceCustomizer.PROP_ENVIRONMENT, "production",
                PlatformResourceCustomizer.PROP_ID, "demo-001"
        );

        // when
        Resource resource = customizer(NO_ENV, NO_BUILD, noHost())
                .customize(Resource.empty(), new TestConfigProperties(values));

        // then
        assertThat(resource.getAttribute(key(PlatformAttributes.SERVICE_NAME))).isEqualTo("demo-service");
        assertThat(resource.getAttribute(key(PlatformAttributes.SERVICE_VERSION))).isEqualTo("1.0.0");
        assertThat(resource.getAttribute(key(PlatformAttributes.PLATFORM_ENVIRONMENT))).isEqualTo("production");
        assertThat(resource.getAttribute(key(PlatformAttributes.PLATFORM_ID))).isEqualTo("demo-001");
    }

    @Test
    void otelServiceNameBlocksPlatformOverride() {
        // given
        Map<String, String> values = Map.of(
                "otel.service.name", "otel-svc",
                PlatformResourceCustomizer.PROP_SERVICE_NAME, "platform-svc");

        // when
        Resource resource = customizer(NO_ENV, NO_BUILD, noHost())
                .customize(Resource.empty(), new TestConfigProperties(values));

        // then
        assertThat(resource.getAttribute(key(PlatformAttributes.SERVICE_NAME))).isNull();
    }

    @Test
    void serviceVersionFromBuildInfoWhenExplicitNotSet() {
        // when
        Resource resource = customizer(NO_ENV, () -> Optional.of("2.17.4"), noHost())
                .customize(Resource.empty(), new TestConfigProperties(Map.of()));

        // then
        assertThat(resource.getAttribute(key(PlatformAttributes.SERVICE_VERSION))).isEqualTo("2.17.4");
    }

    @Test
    void serviceVersionExplicitWinsOverBuildInfo() {
        // given
        Map<String, String> values = Map.of(PlatformResourceCustomizer.PROP_SERVICE_VERSION, "9.9.9");
        AtomicBoolean buildRead = new AtomicBoolean(false);

        // when
        Resource resource = customizer(NO_ENV, () -> {
            buildRead.set(true);
            return Optional.of("2.17.4");
        }, noHost()).customize(Resource.empty(), new TestConfigProperties(values));

        // then
        assertThat(resource.getAttribute(key(PlatformAttributes.SERVICE_VERSION))).isEqualTo("9.9.9");
        assertThat(buildRead).isFalse();
    }

    @Test
    void hostNameOmitWhenUndefined() {
        // when
        Resource resource = customizer(NO_ENV, NO_BUILD, noHost())
                .customize(Resource.empty(), new TestConfigProperties(Map.of()));

        // then
        assertThat(resource.getAttribute(key(PlatformAttributes.PLATFORM_HOST))).isNull();
    }

    @Test
    void hostNameFromHostnameEnv() {
        // given
        HostNameResolver host = new HostNameResolver(
                name -> "HOSTNAME".equals(name) ? "pod-billing-7f9c" : null,
                () -> {
                    throw new AssertionError("InetAddress must not be called when HOSTNAME is present");
                });

        // when
        Resource resource = customizer(NO_ENV, NO_BUILD, host)
                .customize(Resource.empty(), new TestConfigProperties(Map.of()));

        // then
        assertThat(resource.getAttribute(key(PlatformAttributes.PLATFORM_HOST))).isEqualTo("pod-billing-7f9c");
    }

    @Test
    void containerIdExplicitConfig() {
        // given
        Map<String, String> values = Map.of(PlatformResourceCustomizer.PROP_CONTAINER_ID, "docker-abc123");

        // when
        Resource resource = customizer(NO_ENV, NO_BUILD, noHost())
                .customize(Resource.empty(), new TestConfigProperties(values));

        // then
        assertThat(resource.getAttribute(key(PlatformAttributes.CONTAINER_ID))).isEqualTo("docker-abc123");
    }

    @Test
    void containerIdFromEnvironmentWhenConfigBlank() {
        // given
        Function<String, String> env = name -> "CONTAINER_ID".equals(name) ? "env-container" : null;

        // when
        Resource resource = customizer(env, NO_BUILD, noHost())
                .customize(Resource.empty(), new TestConfigProperties(Map.of()));

        // then
        assertThat(resource.getAttribute(key(PlatformAttributes.CONTAINER_ID))).isEqualTo("env-container");
    }

    @Test
    void k8sPodUidIsNoLongerWritten() {
        // given
        Map<String, String> values = Map.of("platform.tracing.service.k8s-pod-uid", "pod-uid-123");

        // when
        Resource resource = customizer(NO_ENV, NO_BUILD, noHost())
                .customize(Resource.empty(), new TestConfigProperties(values));

        // then
        assertThat(resource.getAttribute(key("k8s.pod.uid"))).isNull();
    }

    @Test
    void platformPropertiesOverrideDetectorIdentity() {
        // given
        Resource existing = Resource.create(Attributes.builder()
                .put(key(PlatformAttributes.SERVICE_NAME), "existing-service")
                .put(key(PlatformAttributes.PLATFORM_HOST), "existing-host")
                .put(key(PlatformAttributes.CONTAINER_ID), "existing-container")
                .build());
        Map<String, String> values = Map.of(
                PlatformResourceCustomizer.PROP_SERVICE_NAME, "platform-service",
                PlatformResourceCustomizer.PROP_HOST, "Platform-Host",
                PlatformResourceCustomizer.PROP_CONTAINER_ID, "platform-container");

        // when
        Resource resource = customizer(NO_ENV, NO_BUILD, noHost())
                .customize(existing, new TestConfigProperties(values));

        // then
        assertThat(resource.getAttribute(key(PlatformAttributes.SERVICE_NAME))).isEqualTo("platform-service");
        assertThat(resource.getAttribute(key(PlatformAttributes.PLATFORM_HOST))).isEqualTo("platform-host");
        assertThat(resource.getAttribute(key(PlatformAttributes.CONTAINER_ID))).isEqualTo("platform-container");
    }

    @Test
    void otelEnvironmentStaysAheadOfPlatformProperties() {
        // given
        Resource existing = Resource.create(
                Attributes.builder()
                        .put(key(PlatformAttributes.SERVICE_NAME), "otel-svc")
                        .put(key(PlatformAttributes.PLATFORM_HOST), "otel-host")
                        .put(key(PlatformAttributes.CONTAINER_ID), "otel-container")
                        .build()
        );

        Map<String, String> values = Map.of(
                "otel.service.name", "otel-svc",
                "otel.resource.attributes", "host.name=otel-host,container.id=otel-container",
                PlatformResourceCustomizer.PROP_SERVICE_NAME, "platform-service",
                PlatformResourceCustomizer.PROP_HOST, "platform-host",
                PlatformResourceCustomizer.PROP_CONTAINER_ID, "platform-container");

        // when
        Resource resource = customizer(NO_ENV, NO_BUILD, noHost())
                .customize(existing, new TestConfigProperties(values));

        // then
        assertThat(resource.getAttribute(key(PlatformAttributes.SERVICE_NAME))).isEqualTo("otel-svc");
        assertThat(resource.getAttribute(key(PlatformAttributes.PLATFORM_HOST))).isEqualTo("otel-host");
        assertThat(resource.getAttribute(key(PlatformAttributes.CONTAINER_ID))).isEqualTo("otel-container");
    }

    @Test
    void detectorNameIsExportedAndLoggedWhenPlatformNameIsAbsent() {
        // given
        AgentLogCapture logs = new AgentLogCapture();
        logs.install();

        Resource existing = Resource.create(Attributes.of(key(PlatformAttributes.SERVICE_NAME), "from-detector"));
        Map<String, String> values = Map.of(PlatformResourceCustomizer.PROP_ENVIRONMENT, "production");
        try {
            // when
            Resource resource = customizer(NO_ENV, NO_BUILD, noHost())
                    .customize(existing, new TestConfigProperties(values));

            // then
            assertThat(resource.getAttribute(key(PlatformAttributes.SERVICE_NAME))).isEqualTo("from-detector");
            assertThat(logs.events())
                    .filteredOn(event -> "Resource identity resolved".equals(event.message()))
                    .singleElement()
                    .satisfies(event -> assertThat(event.fields()).containsEntry("service", "from-detector"));
        } finally {
            logs.restore();
        }
    }

    @Test
    void disabledKeyAlreadyPresentOnExistingResourceIsNotStripped() {
        // given
        Resource existing = Resource.create(Attributes.of(key(PlatformAttributes.PLATFORM_HOST), "kept-host"));
        Map<String, String> values = Map.of(
                "otel.resource.disabled.keys", PlatformAttributes.PLATFORM_HOST,
                PlatformResourceCustomizer.PROP_HOST, "platform-host",
                PlatformResourceCustomizer.PROP_SERVICE_NAME, "demo-service",
                PlatformResourceCustomizer.PROP_ENVIRONMENT, "production");

        // when
        Resource resource = customizer(NO_ENV, NO_BUILD, noHost())
                .customize(existing, new TestConfigProperties(values));

        // then
        assertThat(resource.getAttribute(key(PlatformAttributes.PLATFORM_HOST))).isEqualTo("kept-host");
    }

    @Test
    void disabledKeyIsNotProjectedByPlatform() {
        // given
        Map<String, String> values = Map.of(
                "otel.resource.disabled.keys", PlatformAttributes.PLATFORM_HOST,
                PlatformResourceCustomizer.PROP_HOST, "platform-host",
                PlatformResourceCustomizer.PROP_SERVICE_NAME, "demo-service",
                PlatformResourceCustomizer.PROP_ENVIRONMENT, "production");

        // when
        Resource resource = customizer(NO_ENV, NO_BUILD, noHost())
                .customize(Resource.empty(), new TestConfigProperties(values));

        // then
        assertThat(resource.getAttribute(key(PlatformAttributes.PLATFORM_HOST))).isNull();
        assertThat(resource.getAttribute(key(PlatformAttributes.SERVICE_NAME))).isEqualTo("demo-service");
    }

    @Test
    void errorFailsWhenRequiredKeysAbsent() {
        // given
        Map<String, String> values = Map.of(ExtensionPropertyNames.RESOURCE_VALIDATION_MODE, "ERROR");
        PlatformResourceCustomizer customizer = customizer(NO_ENV, NO_BUILD, noHost());

        // when/then
        assertThatThrownBy(() -> customizer.customize(Resource.empty(), new TestConfigProperties(values)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing required keys");
    }

    @Test
    void warningLogsIncompleteIdentityWithoutFailing() {
        // given
        AgentLogCapture logs = new AgentLogCapture();
        logs.install();
        try {
            // when
            Resource resource = customizer(NO_ENV, NO_BUILD, noHost())
                    .customize(Resource.empty(), new TestConfigProperties(Map.of()));

            // then
            assertThat(resource.getAttribute(key(PlatformAttributes.SERVICE_NAME))).isNull();
            assertThat(logs.events())
                    .filteredOn(event -> event.level() == AgentLogLevel.WARN)
                    .extracting(CapturedLogEvent::message)
                    .contains("Resource identity incomplete");
        } finally {
            logs.restore();
        }
    }

    @Test
    void completeIdentityIsLoggedAtInfo() {
        // given
        AgentLogCapture logs = new AgentLogCapture();
        logs.install();
        Map<String, String> values = Map.of(
                PlatformResourceCustomizer.PROP_SERVICE_NAME, "demo-service",
                PlatformResourceCustomizer.PROP_ENVIRONMENT, "production");
        try {
            // when
            customizer(NO_ENV, NO_BUILD, noHost())
                    .customize(Resource.empty(), new TestConfigProperties(values));

            // then
            assertThat(logs.events())
                    .filteredOn(event -> "Resource identity resolved".equals(event.message()))
                    .extracting(CapturedLogEvent::level)
                    .containsExactly(AgentLogLevel.INFO);
        } finally {
            logs.restore();
        }
    }

    @Test
    void errorDoesNotFailWithOtelServiceNameEffectiveView() {
        // given
        Map<String, String> values = Map.of(
                ExtensionPropertyNames.RESOURCE_VALIDATION_MODE, "ERROR",
                "otel.service.name", "otel-svc",
                PlatformResourceCustomizer.PROP_ENVIRONMENT, "production");

        // when
        Resource resource = customizer(NO_ENV, NO_BUILD, noHost())
                .customize(Resource.empty(), new TestConfigProperties(values));

        // then
        assertThat(resource.getAttribute(key(PlatformAttributes.SERVICE_NAME))).isNull();
        assertThat(resource.getAttribute(key(PlatformAttributes.PLATFORM_ENVIRONMENT))).isEqualTo("production");
    }

    @Test
    void warningReturnsExistingAndCountsCustomizationFailure() {
        // given
        TracingDiagnostics diagnostics = new TracingDiagnostics();
        HostNameResolver failingHost = new HostNameResolver(NO_ENV, () -> {
            throw new IllegalStateException("injected hostname failure");
        });
        Resource existing = Resource.create(Attributes.of(key(PlatformAttributes.SERVICE_NAME), "kept"));
        AgentLogCapture logs = new AgentLogCapture();
        logs.install();
        try {
            // when
            Resource resource = customizer(NO_ENV, NO_BUILD, failingHost, diagnostics)
                    .customize(existing, new TestConfigProperties(Map.of()));

            // then
            assertThat(resource.getAttribute(key(PlatformAttributes.SERVICE_NAME))).isEqualTo("kept");
            assertThat(diagnostics.getResourceFailures()).isEqualTo(1);
            assertThat(logs.events())
                    .filteredOn(event -> "Resource customization failed".equals(event.message()))
                    .extracting(CapturedLogEvent::level)
                    .containsExactly(AgentLogLevel.WARN);
        } finally {
            logs.restore();
        }
    }

    @Test
    void errorPropagatesCustomizationFailure() {
        // given
        TracingDiagnostics diagnostics = new TracingDiagnostics();
        HostNameResolver failingHost = new HostNameResolver(NO_ENV, () -> {
            throw new IllegalStateException("injected hostname failure");
        });
        Map<String, String> values = Map.of(ExtensionPropertyNames.RESOURCE_VALIDATION_MODE, "ERROR");

        // when/then
        assertThatThrownBy(() -> customizer(NO_ENV, NO_BUILD, failingHost, diagnostics)
                .customize(Resource.empty(), new TestConfigProperties(values)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("injected hostname failure");
        assertThat(diagnostics.getResourceFailures()).isZero();
    }

    @Test
    void invalidValidationModeFailsBeforeCustomization() {
        // given
        TracingDiagnostics diagnostics = new TracingDiagnostics();
        Map<String, String> values = Map.of(ExtensionPropertyNames.RESOURCE_VALIDATION_MODE, "NOPE");

        // when/then
        assertThatThrownBy(() -> customizer(NO_ENV, NO_BUILD, noHost(), diagnostics)
                .customize(Resource.empty(), new TestConfigProperties(values)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("platform.tracing.resource.validation-mode");
        assertThat(diagnostics.getResourceFailures()).isZero();
    }
}
