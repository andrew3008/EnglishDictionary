package space.br1440.platform.tracing.otel.javaagent.resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.resources.Resource;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames;

/**
 * Поведение {@link PlatformResourceCustomizer}: per-key omit, post-provider merge,
 * host omit-if-unknown, container opt-in, disabled keys и валидация на старте.
 */
class PlatformResourceCustomizerTest {

    private static final Function<String, String> NO_ENV = name -> null;
    private static final Supplier<Optional<String>> NO_CONTAINER = Optional::empty;

    private static AttributeKey<String> key(String name) {
        return AttributeKey.stringKey(name);
    }

    private static PlatformResourceCustomizer customizer(Function<String, String> env,
                                                          Supplier<Optional<String>> buildVersion,
                                                          Supplier<Optional<String>> manifestVersion,
                                                          Supplier<Optional<String>> container,
                                                          HostNameResolver host) {
        return new PlatformResourceCustomizer(
                new ResourceAttributeResolver(),
                host,
                new ResourceValidationDiagnostics(),
                env,
                container,
                buildVersion,
                manifestVersion);
    }

    /** Хост-резолвер, который никогда не находит имя (omit-сценарий). */
    private static HostNameResolver noHost() {
        return new HostNameResolver(NO_ENV, () -> null);
    }

    @Test
    void platform_properties_fill_identity() {
        Map<String, String> values = Map.of(
                PlatformResourceCustomizer.PROP_SERVICE_NAME, "demo-service",
                PlatformResourceCustomizer.PROP_SERVICE_VERSION, "1.0.0",
                PlatformResourceCustomizer.PROP_ENVIRONMENT, "prod",
                PlatformResourceCustomizer.PROP_C_GROUP, "billing",
                PlatformResourceCustomizer.PROP_ID, "demo-001");

        Resource resource = customizer(NO_ENV, Optional::empty, Optional::empty, NO_CONTAINER, noHost())
                .customize(Resource.empty(), new TestConfigProperties(values));

        assertThat(resource.getAttribute(key(PlatformAttributes.SERVICE_NAME))).isEqualTo("demo-service");
        assertThat(resource.getAttribute(key(PlatformAttributes.SERVICE_VERSION))).isEqualTo("1.0.0");
        assertThat(resource.getAttribute(key(PlatformAttributes.PLATFORM_ENVIRONMENT))).isEqualTo("production");
        assertThat(resource.getAttribute(key(PlatformAttributes.PLATFORM_C_GROUP))).isEqualTo("billing");
        assertThat(resource.getAttribute(key(PlatformAttributes.PLATFORM_ID))).isEqualTo("demo-001");
    }

    @Test
    void otel_service_name_blocks_platform_override() {
        // OTEL_SERVICE_NAME задан → provider ОМИТИТ service.name (его допишет EnvironmentResourceProvider).
        Map<String, String> values = Map.of(
                "otel.service.name", "otel-svc",
                PlatformResourceCustomizer.PROP_SERVICE_NAME, "platform-svc");

        Resource resource = customizer(NO_ENV, Optional::empty, Optional::empty, NO_CONTAINER, noHost())
                .customize(Resource.empty(), new TestConfigProperties(values));

        assertThat(resource.getAttribute(key(PlatformAttributes.SERVICE_NAME))).isNull();
    }

    @Test
    void service_version_from_build_info_when_explicit_not_set() {
        Resource resource = customizer(NO_ENV, () -> Optional.of("2.17.4"), Optional::empty, NO_CONTAINER, noHost())
                .customize(Resource.empty(), new TestConfigProperties(Map.of()));

        assertThat(resource.getAttribute(key(PlatformAttributes.SERVICE_VERSION))).isEqualTo("2.17.4");
    }

    @Test
    void service_version_explicit_wins_over_build_info() {
        Map<String, String> values = Map.of(PlatformResourceCustomizer.PROP_SERVICE_VERSION, "9.9.9");

        Resource resource = customizer(NO_ENV, () -> Optional.of("2.17.4"), Optional::empty, NO_CONTAINER, noHost())
                .customize(Resource.empty(), new TestConfigProperties(values));

        assertThat(resource.getAttribute(key(PlatformAttributes.SERVICE_VERSION))).isEqualTo("9.9.9");
    }

    @Test
    void host_name_omit_when_undefined() {
        Resource resource = customizer(NO_ENV, Optional::empty, Optional::empty, NO_CONTAINER, noHost())
                .customize(Resource.empty(), new TestConfigProperties(Map.of()));

        // omit-if-unknown: фейковый "unknown" не пишется
        assertThat(resource.getAttribute(key(PlatformAttributes.PLATFORM_HOST))).isNull();
    }

    @Test
    void host_name_from_HOSTNAME_env() {
        HostNameResolver host = new HostNameResolver(
                name -> "HOSTNAME".equals(name) ? "pod-billing-7f9c" : null,
                () -> {
                    throw new AssertionError("InetAddress must not be called when HOSTNAME is present");
                });

        Resource resource = customizer(NO_ENV, Optional::empty, Optional::empty, NO_CONTAINER, host)
                .customize(Resource.empty(), new TestConfigProperties(Map.of()));

        assertThat(resource.getAttribute(key(PlatformAttributes.PLATFORM_HOST))).isEqualTo("pod-billing-7f9c");
    }

    @Test
    void container_id_explicit_config() {
        Map<String, String> values = Map.of(PlatformResourceCustomizer.PROP_CONTAINER_ID, "docker-abc123");

        Resource resource = customizer(NO_ENV, Optional::empty, Optional::empty, NO_CONTAINER, noHost())
                .customize(Resource.empty(), new TestConfigProperties(values));

        assertThat(resource.getAttribute(key(PlatformAttributes.CONTAINER_ID))).isEqualTo("docker-abc123");
    }

    @Test
    void container_id_procfs_only_with_opt_in() {
        Supplier<Optional<String>> procfs = () -> Optional.of("procfs-id");

        // detect-container-id не задан (default false) → procfs НЕ вызывается, container.id отсутствует
        Resource off = customizer(NO_ENV, Optional::empty, Optional::empty, procfs, noHost())
                .customize(Resource.empty(), new TestConfigProperties(Map.of()));
        assertThat(off.getAttribute(key(PlatformAttributes.CONTAINER_ID))).isNull();

        // detect-container-id=true → procfs подхватывается
        Resource on = customizer(NO_ENV, Optional::empty, Optional::empty, procfs, noHost())
                .customize(Resource.empty(), new TestConfigProperties(
                        Map.of(ExtensionPropertyNames.RESOURCE_DETECT_CONTAINER_ID, "true")));
        assertThat(on.getAttribute(key(PlatformAttributes.CONTAINER_ID))).isEqualTo("procfs-id");
    }

    @Test
    void k8s_pod_uid_is_no_longer_written() {
        // Ключ k8s.pod.uid удалён из провайдера (зона Collector). Даже при заданном старом свойстве — absent.
        Map<String, String> values = Map.of("platform.tracing.service.k8s-pod-uid", "pod-uid-123");

        Resource resource = customizer(NO_ENV, Optional::empty, Optional::empty, NO_CONTAINER, noHost())
                .customize(Resource.empty(), new TestConfigProperties(values));

        assertThat(resource.getAttribute(key("k8s.pod.uid"))).isNull();
    }

    @Test
    void existing_resource_wins_over_platform_fallback() {
        // given
        Resource existing = Resource.create(Attributes.builder()
                .put(key(PlatformAttributes.SERVICE_NAME), "existing-service")
                .put(key(PlatformAttributes.PLATFORM_HOST), "existing-host")
                .put(key(PlatformAttributes.CONTAINER_ID), "existing-container")
                .build());
        Map<String, String> values = Map.of(
                PlatformResourceCustomizer.PROP_SERVICE_NAME, "platform-service",
                PlatformResourceCustomizer.PROP_HOST, "platform-host",
                PlatformResourceCustomizer.PROP_CONTAINER_ID, "platform-container");

        // when
        Resource resource = customizer(NO_ENV, Optional::empty, Optional::empty, NO_CONTAINER, noHost())
                .customize(existing, new TestConfigProperties(values));

        // then
        assertThat(resource.getAttribute(key(PlatformAttributes.SERVICE_NAME))).isEqualTo("existing-service");
        assertThat(resource.getAttribute(key(PlatformAttributes.PLATFORM_HOST))).isEqualTo("existing-host");
        assertThat(resource.getAttribute(key(PlatformAttributes.CONTAINER_ID))).isEqualTo("existing-container");
    }

    @Test
    void strict_fails_when_required_keys_absent() {
        Map<String, String> values = Map.of(ExtensionPropertyNames.RESOURCE_VALIDATION_MODE, "STRICT");

        PlatformResourceCustomizer customizer =
                customizer(NO_ENV, Optional::empty, Optional::empty, NO_CONTAINER, noHost());

        assertThatThrownBy(() -> customizer.customize(Resource.empty(), new TestConfigProperties(values)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void strict_does_not_fail_with_otel_service_name_effective_view() {
        // RV-08: service.name приходит через OTEL_*, env/c_group заданы — STRICT НЕ должен падать.
        Map<String, String> values = Map.of(
                ExtensionPropertyNames.RESOURCE_VALIDATION_MODE, "STRICT",
                "otel.service.name", "otel-svc",
                PlatformResourceCustomizer.PROP_ENVIRONMENT, "production",
                PlatformResourceCustomizer.PROP_C_GROUP, "billing");

        Resource resource = customizer(NO_ENV, Optional::empty, Optional::empty, NO_CONTAINER, noHost())
                .customize(Resource.empty(), new TestConfigProperties(values));

        assertThat(resource.getAttribute(key(PlatformAttributes.SERVICE_NAME))).isNull();
        assertThat(resource.getAttribute(key(PlatformAttributes.PLATFORM_C_GROUP))).isEqualTo("billing");
    }

    @Test
    void legacy_provider_controls_are_rejected_explicitly() {
        // given
        TestConfigProperties config = new TestConfigProperties(Map.of(
                "otel.java.disabled.resource.providers",
                "space.br1440.platform.tracing.otel.javaagent.resource.SafeResourceProvider"));

        // when
        PlatformResourceCustomizer customizer =
                customizer(NO_ENV, Optional::empty, Optional::empty, NO_CONTAINER, noHost());

        // then
        assertThatThrownBy(() -> customizer.customize(Resource.empty(), config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Legacy SafeResourceProvider controls are unsupported");
    }
}
