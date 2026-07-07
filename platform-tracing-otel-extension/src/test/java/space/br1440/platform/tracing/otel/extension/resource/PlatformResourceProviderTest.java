package space.br1440.platform.tracing.otel.extension.resource;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.resources.Resource;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.otel.extension.configuration.ExtensionDefaults;
import space.br1440.platform.tracing.otel.extension.configuration.ExtensionPropertyNames;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Поведение {@link PlatformResourceProvider} после рефакторинга Фазы 9 (per-key omit, order=100,
 * host omit-if-unknown, container opt-in, удаление k8s.pod.uid, валидация на старте).
 */
class PlatformResourceProviderTest {

    private static final Function<String, String> NO_ENV = name -> null;
    private static final Supplier<Optional<String>> NO_CONTAINER = Optional::empty;

    private static AttributeKey<String> key(String name) {
        return AttributeKey.stringKey(name);
    }

    private static PlatformResourceProvider provider(Function<String, String> env,
                                                     Supplier<Optional<String>> buildVersion,
                                                     Supplier<Optional<String>> manifestVersion,
                                                     Supplier<Optional<String>> container,
                                                     HostNameResolver host) {
        return new PlatformResourceProvider(
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
    void платформенные_свойства_заполняют_identity() {
        Map<String, String> values = Map.of(
                PlatformResourceProvider.PROP_SERVICE_NAME, "demo-service",
                PlatformResourceProvider.PROP_SERVICE_VERSION, "1.0.0",
                PlatformResourceProvider.PROP_ENVIRONMENT, "prod",
                PlatformResourceProvider.PROP_C_GROUP, "billing",
                PlatformResourceProvider.PROP_ID, "demo-001");

        Resource resource = provider(NO_ENV, Optional::empty, Optional::empty, NO_CONTAINER, noHost())
                .createResource(new TestConfigProperties(values));

        assertThat(resource.getAttribute(key(PlatformAttributes.SERVICE_NAME))).isEqualTo("demo-service");
        assertThat(resource.getAttribute(key(PlatformAttributes.SERVICE_VERSION))).isEqualTo("1.0.0");
        // environment нормализуется: prod -> production
        assertThat(resource.getAttribute(key(PlatformAttributes.PLATFORM_ENVIRONMENT))).isEqualTo("production");
        assertThat(resource.getAttribute(key(PlatformAttributes.PLATFORM_C_GROUP))).isEqualTo("billing");
        assertThat(resource.getAttribute(key(PlatformAttributes.PLATFORM_ID))).isEqualTo("demo-001");
        assertThat(resource.getAttribute(key(ExtensionPropertyNames.RESOURCE_POLICY_VERSION_ATTR)))
                .isEqualTo(ExtensionDefaults.DEFAULT_RESOURCE_POLICY_VERSION);
    }

    @Test
    void otel_service_name_блокирует_platform_override() {
        // OTEL_SERVICE_NAME задан → provider ОМИТИТ service.name (его допишет EnvironmentResourceProvider).
        Map<String, String> values = Map.of(
                "otel.service.name", "otel-svc",
                PlatformResourceProvider.PROP_SERVICE_NAME, "platform-svc");

        Resource resource = provider(NO_ENV, Optional::empty, Optional::empty, NO_CONTAINER, noHost())
                .createResource(new TestConfigProperties(values));

        assertThat(resource.getAttribute(key(PlatformAttributes.SERVICE_NAME))).isNull();
    }

    @Test
    void service_version_из_build_info_когда_explicit_не_задан() {
        Resource resource = provider(NO_ENV, () -> Optional.of("2.17.4"), Optional::empty, NO_CONTAINER, noHost())
                .createResource(new TestConfigProperties(Map.of()));

        assertThat(resource.getAttribute(key(PlatformAttributes.SERVICE_VERSION))).isEqualTo("2.17.4");
    }

    @Test
    void service_version_explicit_побеждает_build_info() {
        Map<String, String> values = Map.of(PlatformResourceProvider.PROP_SERVICE_VERSION, "9.9.9");

        Resource resource = provider(NO_ENV, () -> Optional.of("2.17.4"), Optional::empty, NO_CONTAINER, noHost())
                .createResource(new TestConfigProperties(values));

        assertThat(resource.getAttribute(key(PlatformAttributes.SERVICE_VERSION))).isEqualTo("9.9.9");
    }

    @Test
    void host_name_omit_когда_не_определён() {
        Resource resource = provider(NO_ENV, Optional::empty, Optional::empty, NO_CONTAINER, noHost())
                .createResource(new TestConfigProperties(Map.of()));

        // omit-if-unknown: фейковый "unknown" не пишется
        assertThat(resource.getAttribute(key(PlatformAttributes.PLATFORM_HOST))).isNull();
    }

    @Test
    void host_name_из_HOSTNAME_env() {
        HostNameResolver host = new HostNameResolver(
                name -> "HOSTNAME".equals(name) ? "pod-billing-7f9c" : null,
                () -> {
                    throw new AssertionError("InetAddress не должен вызываться при наличии HOSTNAME");
                });

        Resource resource = provider(NO_ENV, Optional::empty, Optional::empty, NO_CONTAINER, host)
                .createResource(new TestConfigProperties(Map.of()));

        assertThat(resource.getAttribute(key(PlatformAttributes.PLATFORM_HOST))).isEqualTo("pod-billing-7f9c");
    }

    @Test
    void container_id_explicit_config() {
        Map<String, String> values = Map.of(PlatformResourceProvider.PROP_CONTAINER_ID, "docker-abc123");

        Resource resource = provider(NO_ENV, Optional::empty, Optional::empty, NO_CONTAINER, noHost())
                .createResource(new TestConfigProperties(values));

        assertThat(resource.getAttribute(key(PlatformAttributes.CONTAINER_ID))).isEqualTo("docker-abc123");
    }

    @Test
    void container_id_procfs_только_при_opt_in() {
        Supplier<Optional<String>> procfs = () -> Optional.of("procfs-id");

        // detect-container-id не задан (default false) → procfs НЕ вызывается, container.id отсутствует
        Resource off = provider(NO_ENV, Optional::empty, Optional::empty, procfs, noHost())
                .createResource(new TestConfigProperties(Map.of()));
        assertThat(off.getAttribute(key(PlatformAttributes.CONTAINER_ID))).isNull();

        // detect-container-id=true → procfs подхватывается
        Resource on = provider(NO_ENV, Optional::empty, Optional::empty, procfs, noHost())
                .createResource(new TestConfigProperties(
                        Map.of(ExtensionPropertyNames.RESOURCE_DETECT_CONTAINER_ID, "true")));
        assertThat(on.getAttribute(key(PlatformAttributes.CONTAINER_ID))).isEqualTo("procfs-id");
    }

    @Test
    void k8s_pod_uid_больше_не_пишется() {
        // Ключ k8s.pod.uid удалён из провайдера (зона Collector). Даже при заданном старом свойстве — absent.
        Map<String, String> values = Map.of("platform.tracing.service.k8s-pod-uid", "pod-uid-123");

        Resource resource = provider(NO_ENV, Optional::empty, Optional::empty, NO_CONTAINER, noHost())
                .createResource(new TestConfigProperties(values));

        assertThat(resource.getAttribute(key("k8s.pod.uid"))).isNull();
    }

    @Test
    void order_равен_100() {
        assertThat(new PlatformResourceProvider().order()).isEqualTo(100);
    }

    @Test
    void strict_падает_при_отсутствии_обязательных_ключей() {
        Map<String, String> values = Map.of(ExtensionPropertyNames.RESOURCE_VALIDATION_MODE, "STRICT");

        PlatformResourceProvider provider =
                provider(NO_ENV, Optional::empty, Optional::empty, NO_CONTAINER, noHost());

        assertThatThrownBy(() -> provider.createResource(new TestConfigProperties(values)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void strict_не_падает_при_otel_service_name_effective_view() {
        // RV-08: service.name приходит через OTEL_*, env/c_group заданы — STRICT НЕ должен падать.
        Map<String, String> values = Map.of(
                ExtensionPropertyNames.RESOURCE_VALIDATION_MODE, "STRICT",
                "otel.service.name", "otel-svc",
                PlatformResourceProvider.PROP_ENVIRONMENT, "production",
                PlatformResourceProvider.PROP_C_GROUP, "billing");

        Resource resource = provider(NO_ENV, Optional::empty, Optional::empty, NO_CONTAINER, noHost())
                .createResource(new TestConfigProperties(values));

        // provider омитит service.name (его допишет Environment provider), но STRICT не падает
        assertThat(resource.getAttribute(key(PlatformAttributes.SERVICE_NAME))).isNull();
        assertThat(resource.getAttribute(key(PlatformAttributes.PLATFORM_C_GROUP))).isEqualTo("billing");
    }
}
