package space.br1440.platform.tracing.otel.javaagent.resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames;
import space.br1440.platform.tracing.otel.javaagent.configuration.ResourceValidationMode;
import space.br1440.platform.tracing.otel.javaagent.diagnostics.TracingDiagnostics;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;
import space.br1440.platform.tracing.otel.javaagent.logging.CapturedLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.resources.Resource;

@ResourceLock(AgentLogCapture.RESOURCE_LOCK)
class PlatformResourceCustomizerTest {

    private static final String RESOLVED = "Resource identity resolved";
    private static final String INCOMPLETE = "Resource identity incomplete";
    private static final String CUSTOMIZATION_FAILED = "Resource customization failed";

    private static final UnaryOperator<String> NO_ENV = name -> null;
    private static final Supplier<Optional<String>> NO_BUILD = Optional::empty;

    private static AttributeKey<String> key(String name) {
        return AttributeKey.stringKey(name);
    }

    private static PlatformResourceCustomizer customizer(UnaryOperator<String> env,
                                                         Supplier<Optional<String>> buildVersion,
                                                         TracingDiagnostics diagnostics) {
        return new PlatformResourceCustomizer(env, buildVersion, diagnostics);
    }

    private static PlatformResourceCustomizer customizer(UnaryOperator<String> env,
                                                         Supplier<Optional<String>> buildVersion) {
        return customizer(env, buildVersion, new TracingDiagnostics());
    }

    private static PlatformResourceCustomizer customizer() {
        return customizer(NO_ENV, NO_BUILD);
    }

    private static Resource customize(PlatformResourceCustomizer customizer,
                                      Resource existing,
                                      Map<String, String> values) {
        return customizer.customize(existing, new TestConfigProperties(values), ResourceValidationMode.WARNING);
    }

    private static Resource existingWith(String attribute, String value) {
        return Resource.create(Attributes.of(key(attribute), value));
    }

    @Test
    void platformPropertiesFillIdentity() {
        // given
        Map<String, String> values = Map.of(
                ExtensionPropertyNames.SERVICE_NAME, "demo-service",
                ExtensionPropertyNames.SERVICE_VERSION, "1.0.0",
                ExtensionPropertyNames.SERVICE_ENVIRONMENT, "production",
                ExtensionPropertyNames.SERVICE_HOST, "Host-01",
                ExtensionPropertyNames.SERVICE_CONTAINER_ID, "docker-abc123");

        // when
        Resource resource = customize(customizer(), Resource.empty(), values);

        // then
        assertThat(resource.getAttribute(key(PlatformAttributes.SERVICE_NAME))).isEqualTo("demo-service");
        assertThat(resource.getAttribute(key(PlatformAttributes.SERVICE_VERSION))).isEqualTo("1.0.0");
        assertThat(resource.getAttribute(key(PlatformAttributes.PLATFORM_ENVIRONMENT))).isEqualTo("production");
        assertThat(resource.getAttribute(key(PlatformAttributes.PLATFORM_HOST))).isEqualTo("Host-01");
        assertThat(resource.getAttribute(key(PlatformAttributes.CONTAINER_ID))).isEqualTo("docker-abc123");
    }

    @Test
    void platformPropertiesAreStripped() {
        // given
        Map<String, String> values = Map.of(
                ExtensionPropertyNames.SERVICE_NAME, "  demo-service ",
                ExtensionPropertyNames.SERVICE_VERSION, " 1.0.0\t");

        // when
        Resource resource = customize(customizer(), Resource.empty(), values);

        // then
        assertThat(resource.getAttribute(key(PlatformAttributes.SERVICE_NAME))).isEqualTo("demo-service");
        assertThat(resource.getAttribute(key(PlatformAttributes.SERVICE_VERSION))).isEqualTo("1.0.0");
    }

    @Test
    void otelServiceNameBlocksPlatformOverride() {
        // given
        Resource existing = existingWith(PlatformAttributes.SERVICE_NAME, "otel-svc");
        Map<String, String> values = Map.of(
                "otel.service.name", "otel-svc",
                ExtensionPropertyNames.SERVICE_NAME, "platform-svc"
        );

        // when
        Resource resource = customize(customizer(), existing, values);

        // then
        assertThat(resource.getAttribute(key(PlatformAttributes.SERVICE_NAME))).isEqualTo("otel-svc");
    }

    @Test
    void otelResourceAttributesStayAheadOfPlatformProperties() {
        // given
        Resource existing = Resource.create(Attributes.builder()
                .put(key(PlatformAttributes.SERVICE_NAME), "otel-svc")
                .put(key(PlatformAttributes.PLATFORM_HOST), "otel-host")
                .put(key(PlatformAttributes.CONTAINER_ID), "otel-container")
                .build());
        Map<String, String> values = Map.of(
                "otel.service.name", "otel-svc",
                "otel.resource.attributes", "host.name=otel-host,container.id=otel-container",
                ExtensionPropertyNames.SERVICE_NAME, "platform-service",
                ExtensionPropertyNames.SERVICE_HOST, "platform-host",
                ExtensionPropertyNames.SERVICE_CONTAINER_ID, "platform-container");

        // when
        Resource resource = customize(customizer(), existing, values);

        // then
        assertThat(resource.getAttribute(key(PlatformAttributes.SERVICE_NAME))).isEqualTo("otel-svc");
        assertThat(resource.getAttribute(key(PlatformAttributes.PLATFORM_HOST))).isEqualTo("otel-host");
        assertThat(resource.getAttribute(key(PlatformAttributes.CONTAINER_ID))).isEqualTo("otel-container");
    }

    @Test
    void platformPropertiesOverrideExistingIdentity() {
        // given
        Resource existing = Resource.create(Attributes.builder()
                .put(key(PlatformAttributes.SERVICE_NAME), "existing-service")
                .put(key(PlatformAttributes.PLATFORM_HOST), "existing-host")
                .put(key(PlatformAttributes.CONTAINER_ID), "existing-container")
                .build());
        Map<String, String> values = Map.of(
                ExtensionPropertyNames.SERVICE_NAME, "platform-service",
                ExtensionPropertyNames.SERVICE_HOST, "Platform-Host",
                ExtensionPropertyNames.SERVICE_CONTAINER_ID, "platform-container");

        // when
        Resource resource = customize(customizer(), existing, values);

        // then
        assertThat(resource.getAttribute(key(PlatformAttributes.SERVICE_NAME))).isEqualTo("platform-service");
        assertThat(resource.getAttribute(key(PlatformAttributes.PLATFORM_HOST))).isEqualTo("Platform-Host");
        assertThat(resource.getAttribute(key(PlatformAttributes.CONTAINER_ID))).isEqualTo("platform-container");
    }

    @Test
    void serviceVersionFromBuildInfoWhenExplicitNotSet() {
        // when
        Resource resource = customize(customizer(NO_ENV, () -> Optional.of("2.17.4")), Resource.empty(), Map.of());

        // then
        assertThat(resource.getAttribute(key(PlatformAttributes.SERVICE_VERSION))).isEqualTo("2.17.4");
    }

    @Test
    void serviceVersionExplicitWinsOverBuildInfo() {
        // given
        Map<String, String> values = Map.of(ExtensionPropertyNames.SERVICE_VERSION, "9.9.9");
        AtomicBoolean buildRead = new AtomicBoolean(false);
        PlatformResourceCustomizer customizer = customizer(NO_ENV, () -> {
            buildRead.set(true);
            return Optional.of("2.17.4");
        });

        // when
        Resource resource = customize(customizer, Resource.empty(), values);

        // then
        assertThat(resource.getAttribute(key(PlatformAttributes.SERVICE_VERSION))).isEqualTo("9.9.9");
        assertThat(buildRead).isFalse();
    }

    @Test
    void existingVersionWinsOverBuildInfo() {
        // given
        Resource existing = existingWith(PlatformAttributes.SERVICE_VERSION, "existing-version");
        AtomicBoolean buildRead = new AtomicBoolean(false);
        PlatformResourceCustomizer customizer = customizer(NO_ENV, () -> {
            buildRead.set(true);
            return Optional.of("2.17.4");
        });

        // when
        Resource resource = customize(customizer, existing, Map.of());

        // then
        assertThat(resource.getAttribute(key(PlatformAttributes.SERVICE_VERSION))).isEqualTo("existing-version");
        assertThat(buildRead).isFalse();
    }

    @Test
    void environmentIsCanonicalized() {
        // given
        Map<String, String> values = Map.of(ExtensionPropertyNames.SERVICE_ENVIRONMENT, " PROD ");

        // when
        Resource resource = customize(customizer(), Resource.empty(), values);

        // then
        assertThat(resource.getAttribute(key(PlatformAttributes.PLATFORM_ENVIRONMENT))).isEqualTo("prod");
    }

    @Test
    void environmentIsNotRenamed() {
        // given
        Map<String, String> values = Map.of(ExtensionPropertyNames.SERVICE_ENVIRONMENT, "staging-eu1");

        // when
        Resource resource = customize(customizer(), Resource.empty(), values);

        // then
        assertThat(resource.getAttribute(key(PlatformAttributes.PLATFORM_ENVIRONMENT))).isEqualTo("staging-eu1");
    }

    @Test
    void platformEnvironmentOverridesExisting() {
        // given
        Resource existing = existingWith(PlatformAttributes.PLATFORM_ENVIRONMENT, "existing-env");
        Map<String, String> values = Map.of(ExtensionPropertyNames.SERVICE_ENVIRONMENT, "Production");

        // when
        Resource resource = customize(customizer(), existing, values);

        // then
        assertThat(resource.getAttribute(key(PlatformAttributes.PLATFORM_ENVIRONMENT))).isEqualTo("production");
    }

    @Test
    void absentEnvironmentIsNotWritten() {
        // when
        Resource resource = customize(customizer(), Resource.empty(), Map.of());

        // then
        assertThat(resource.getAttribute(key(PlatformAttributes.PLATFORM_ENVIRONMENT))).isNull();
    }

    @Test
    void hostNameOmitWhenUndefined() {
        // when
        Resource resource = customize(customizer(), Resource.empty(), Map.of());

        // then
        assertThat(resource.getAttribute(key(PlatformAttributes.PLATFORM_HOST))).isNull();
    }

    @Test
    void hostNameFromHostnameEnvPreservesCase() {
        // given
        UnaryOperator<String> env = name -> switch (name) {
            case "HOSTNAME" -> "Pod-Billing-7f9c";
            case "COMPUTERNAME" -> "ignored-computer";
            default -> null;
        };

        // when
        Resource resource = customize(customizer(env, NO_BUILD), Resource.empty(), Map.of());

        // then
        assertThat(resource.getAttribute(key(PlatformAttributes.PLATFORM_HOST))).isEqualTo("Pod-Billing-7f9c");
    }

    @Test
    void hostNameFallsBackToComputerNameEnv() {
        // given
        UnaryOperator<String> env = name -> switch (name) {
            case "HOSTNAME" -> " ";
            case "COMPUTERNAME" -> "WS-Tallinn-01";
            default -> null;
        };

        // when
        Resource resource = customize(customizer(env, NO_BUILD), Resource.empty(), Map.of());

        // then
        assertThat(resource.getAttribute(key(PlatformAttributes.PLATFORM_HOST))).isEqualTo("WS-Tallinn-01");
    }

    @Test
    void hostNameEnvDoesNotOverrideExisting() {
        // given
        Resource existing = existingWith(PlatformAttributes.PLATFORM_HOST, "upstream-host");
        UnaryOperator<String> env = name -> "HOSTNAME".equals(name) ? "env-host" : null;

        // when
        Resource resource = customize(customizer(env, NO_BUILD), existing, Map.of());

        // then
        assertThat(resource.getAttribute(key(PlatformAttributes.PLATFORM_HOST))).isEqualTo("upstream-host");
    }

    @Test
    void containerIdExplicitConfig() {
        // given
        Map<String, String> values = Map.of(ExtensionPropertyNames.SERVICE_CONTAINER_ID, "docker-abc123");

        // when
        Resource resource = customize(customizer(), Resource.empty(), values);

        // then
        assertThat(resource.getAttribute(key(PlatformAttributes.CONTAINER_ID))).isEqualTo("docker-abc123");
    }

    @Test
    void containerIdEnvIsIgnored() {
        // given
        UnaryOperator<String> env = name -> "CONTAINER_ID".equals(name) ? "env-container" : null;

        // when
        Resource resource = customize(customizer(env, NO_BUILD), Resource.empty(), Map.of());

        // then
        assertThat(resource.getAttribute(key(PlatformAttributes.CONTAINER_ID))).isNull();
    }

    @Test
    void unknownServiceNameCountsAsMissing() {
        // given
        Resource existing = existingWith(PlatformAttributes.SERVICE_NAME, "unknown_service:java");
        Map<String, String> values = Map.of(ExtensionPropertyNames.SERVICE_ENVIRONMENT, "production");
        try (AgentLogCapture logs = AgentLogCapture.installed()) {
            // when
            customize(customizer(), existing, values);

            // then
            assertThat(logs.events())
                    .filteredOn(event -> INCOMPLETE.equals(event.message()))
                    .singleElement()
                    .satisfies(event -> {
                        assertThat(event.fields()).containsEntry("missingKeys", PlatformAttributes.SERVICE_NAME);
                        assertThat(event.fields()).doesNotContainKey("service");
                    });
        }
    }

    @Test
    void unknownServicePrefixOnHostNameIsPreserved() {
        // given
        Resource existing = existingWith(PlatformAttributes.PLATFORM_HOST, "unknown_service:host");
        Map<String, String> values = Map.of(
                ExtensionPropertyNames.SERVICE_NAME, "demo-service",
                ExtensionPropertyNames.SERVICE_ENVIRONMENT, "production");

        // when
        Resource resource = customize(customizer(), existing, values);

        // then
        assertThat(resource.getAttribute(key(PlatformAttributes.PLATFORM_HOST))).isEqualTo("unknown_service:host");
    }

    @Test
    void detectorNameIsExportedAndLoggedWhenPlatformNameIsAbsent() {
        // given
        Resource existing = existingWith(PlatformAttributes.SERVICE_NAME, "from-detector");
        Map<String, String> values = Map.of(ExtensionPropertyNames.SERVICE_ENVIRONMENT, "production");
        try (AgentLogCapture logs = AgentLogCapture.installed()) {
            // when
            Resource resource = customize(customizer(), existing, values);

            // then
            assertThat(resource.getAttribute(key(PlatformAttributes.SERVICE_NAME))).isEqualTo("from-detector");
            assertThat(logs.events())
                    .filteredOn(event -> RESOLVED.equals(event.message()))
                    .singleElement()
                    .satisfies(event -> assertThat(event.fields()).containsEntry("service", "from-detector"));
        }
    }

    @Test
    void disabledKeyAlreadyPresentOnExistingResourceIsNotStripped() {
        // given
        Resource existing = existingWith(PlatformAttributes.PLATFORM_HOST, "kept-host");
        Map<String, String> values = Map.of(
                "otel.resource.disabled.keys", PlatformAttributes.PLATFORM_HOST,
                ExtensionPropertyNames.SERVICE_HOST, "platform-host",
                ExtensionPropertyNames.SERVICE_NAME, "demo-service",
                ExtensionPropertyNames.SERVICE_ENVIRONMENT, "production");

        // when
        Resource resource = customize(customizer(), existing, values);

        // then
        assertThat(resource.getAttribute(key(PlatformAttributes.PLATFORM_HOST))).isEqualTo("kept-host");
    }

    @Test
    void disabledKeyIsNotProjectedByPlatform() {
        // given
        Map<String, String> values = Map.of(
                "otel.resource.disabled.keys", PlatformAttributes.PLATFORM_HOST,
                ExtensionPropertyNames.SERVICE_HOST, "platform-host",
                ExtensionPropertyNames.SERVICE_NAME, "demo-service",
                ExtensionPropertyNames.SERVICE_ENVIRONMENT, "production");

        // when
        Resource resource = customize(customizer(), Resource.empty(), values);

        // then
        assertThat(resource.getAttribute(key(PlatformAttributes.PLATFORM_HOST))).isNull();
        assertThat(resource.getAttribute(key(PlatformAttributes.SERVICE_NAME))).isEqualTo("demo-service");
    }

    @Test
    void errorFailsWhenRequiredKeysAbsent() {
        // given
        PlatformResourceCustomizer customizer = customizer();
        TestConfigProperties config = new TestConfigProperties(Map.of());

        // when/then
        assertThatThrownBy(() -> customizer.customize(Resource.empty(), config, ResourceValidationMode.ERROR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing required keys")
                .hasMessageContaining(PlatformAttributes.SERVICE_NAME + ", " + PlatformAttributes.PLATFORM_ENVIRONMENT);
    }

    @Test
    void errorValidatesMergedResourceNotOnlyAdditions() {
        // given
        Resource existing = existingWith(PlatformAttributes.SERVICE_NAME, "from-detector");
        Map<String, String> values = Map.of(ExtensionPropertyNames.SERVICE_ENVIRONMENT, "production");

        // when
        Resource resource = customizer().customize(
                existing, new TestConfigProperties(values), ResourceValidationMode.ERROR);

        // then
        assertThat(resource.getAttribute(key(PlatformAttributes.SERVICE_NAME))).isEqualTo("from-detector");
        assertThat(resource.getAttribute(key(PlatformAttributes.PLATFORM_ENVIRONMENT))).isEqualTo("production");
    }

    @Test
    void warningLogsIncompleteIdentityWithoutFailing() {
        // given
        try (AgentLogCapture logs = AgentLogCapture.installed()) {
            // when
            Resource resource = customize(customizer(NO_ENV, () -> Optional.of("1.2.3")), Resource.empty(), Map.of());

            // then
            assertThat(resource.getAttribute(key(PlatformAttributes.SERVICE_NAME))).isNull();
            assertThat(logs.events())
                    .filteredOn(event -> INCOMPLETE.equals(event.message()))
                    .singleElement()
                    .satisfies(event -> {
                        assertThat(event.level()).isEqualTo(AgentLogLevel.WARN);
                        assertThat(event.fields())
                                .containsEntry("version", "1.2.3")
                                .containsEntry("missingKeys",
                                        PlatformAttributes.SERVICE_NAME + ", " + PlatformAttributes.PLATFORM_ENVIRONMENT)
                                .doesNotContainKeys("service", "environment", "kind");
                        assertThat(event.fields().values()).doesNotContain("<absent>");
                    });
        }
    }

    @Test
    void completeIdentityIsLoggedAtInfoWithoutKindAndCount() {
        // given
        Map<String, String> values = Map.of(
                ExtensionPropertyNames.SERVICE_NAME, "demo-service",
                ExtensionPropertyNames.SERVICE_ENVIRONMENT, "production");
        try (AgentLogCapture logs = AgentLogCapture.installed()) {
            // when
            customize(customizer(), Resource.empty(), values);

            // then
            assertThat(logs.events())
                    .filteredOn(event -> RESOLVED.equals(event.message()))
                    .singleElement()
                    .satisfies(event -> {
                        assertThat(event.level()).isEqualTo(AgentLogLevel.INFO);
                        assertThat(event.fields())
                                .containsEntry("service", "demo-service")
                                .containsEntry("environment", "production")
                                .doesNotContainKeys("version", "kind", "count");
                    });
        }
    }

    @Test
    void errorDoesNotFailWhenServiceNameComesFromOtelConfiguration() {
        // given
        Resource existing = existingWith(PlatformAttributes.SERVICE_NAME, "otel-svc");
        Map<String, String> values = Map.of(
                "otel.service.name", "otel-svc",
                ExtensionPropertyNames.SERVICE_ENVIRONMENT, "production");

        // when
        Resource resource = customizer().customize(
                existing, new TestConfigProperties(values), ResourceValidationMode.ERROR);

        // then
        assertThat(resource.getAttribute(key(PlatformAttributes.SERVICE_NAME))).isEqualTo("otel-svc");
        assertThat(resource.getAttribute(key(PlatformAttributes.PLATFORM_ENVIRONMENT))).isEqualTo("production");
    }

    @Test
    void warningReturnsExistingAndCountsCustomizationFailure() {
        // given
        TracingDiagnostics diagnostics = new TracingDiagnostics();
        Supplier<Optional<String>> failingBuild = () -> {
            throw new IllegalStateException("injected build info failure");
        };
        Resource existing = existingWith(PlatformAttributes.SERVICE_NAME, "kept");
        try (AgentLogCapture logs = AgentLogCapture.installed()) {
            // when
            Resource resource = customize(customizer(NO_ENV, failingBuild, diagnostics), existing, Map.of());

            // then
            assertThat(resource).isSameAs(existing);
            assertThat(diagnostics.getResourceFailures()).isEqualTo(1);
            assertThat(logs.events())
                    .filteredOn(event -> CUSTOMIZATION_FAILED.equals(event.message()))
                    .extracting(CapturedLogEvent::level)
                    .containsExactly(AgentLogLevel.WARN);
        }
    }

    @Test
    void errorPropagatesRuntimeCustomizationFailure() {
        // given
        TracingDiagnostics diagnostics = new TracingDiagnostics();
        Supplier<Optional<String>> failingBuild = () -> {
            throw new IllegalStateException("injected build info failure");
        };
        PlatformResourceCustomizer customizer = customizer(NO_ENV, failingBuild, diagnostics);
        TestConfigProperties config = new TestConfigProperties(Map.of());

        // when/then
        assertThatThrownBy(() -> customizer.customize(Resource.empty(), config, ResourceValidationMode.ERROR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("injected build info failure");
        assertThat(diagnostics.getResourceFailures()).isZero();
    }

    @Test
    void warningDoesNotSwallowErrors() {
        // given
        TracingDiagnostics diagnostics = new TracingDiagnostics();
        Supplier<Optional<String>> failingBuild = () -> {
            throw new NoClassDefFoundError("injected linkage failure");
        };
        PlatformResourceCustomizer customizer = customizer(NO_ENV, failingBuild, diagnostics);
        TestConfigProperties config = new TestConfigProperties(Map.of());

        // when/then
        assertThatThrownBy(() -> customizer.customize(Resource.empty(), config, ResourceValidationMode.WARNING))
                .isInstanceOf(NoClassDefFoundError.class)
                .hasMessage("injected linkage failure");
        assertThat(diagnostics.getResourceFailures()).isZero();
    }
}
