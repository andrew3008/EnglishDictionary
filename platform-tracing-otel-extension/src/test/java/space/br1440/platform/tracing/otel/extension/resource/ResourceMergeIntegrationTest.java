package space.br1440.platform.tracing.otel.extension.resource;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.autoconfigure.EnvironmentResourceProvider;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.resources.Resource;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Интеграционный тест merge-цепочки с <b>реальным</b> {@link EnvironmentResourceProvider}
 * (public с SDK 1.47.0), а не mock — воспроизводит фактический порядок merge OTel pipeline.
 * <p>
 * Проверяет ключевой контракт Фазы 9: {@code OTEL_SERVICE_NAME} побеждает
 * {@code platform.tracing.service.name} (per-key omit + порядок merge: Environment provider,
 * order=MAX-1, мержится последним и перетирает).
 */
class ResourceMergeIntegrationTest {

    private static final AttributeKey<String> SERVICE_NAME =
            AttributeKey.stringKey(PlatformAttributes.SERVICE_NAME);

    private final EnvironmentResourceProvider envProvider = new EnvironmentResourceProvider();

    private static PlatformResourceProvider platformProvider() {
        return new PlatformResourceProvider(
                new ResourceAttributeResolver(),
                new HostNameResolver(name -> null, () -> null),
                new ResourceValidationDiagnostics(),
                name -> null,
                Optional::empty,
                Optional::empty,
                Optional::empty);
    }

    /**
     * Реплика merge-loop {@code ResourceConfiguration}: провайдеры по возрастанию order(),
     * каждый последующий {@code result.merge(...)} перетирает предыдущий.
     */
    private Resource merge(ConfigProperties config) {
        PlatformResourceProvider platform = platformProvider();
        Resource result = Resource.getDefault();
        // platform order=100 — раньше env (order=MAX-1)
        if (platform.shouldApply(config, result)) {
            result = result.merge(platform.createResource(config));
        }
        // EnvironmentResourceProvider — последним
        result = result.merge(envProvider.createResource(config));
        return result;
    }

    @Test
    void otel_service_name_побеждает_platform() {
        Resource resource = merge(new TestConfigProperties(Map.of(
                "otel.service.name", "otel-svc",
                PlatformResourceProvider.PROP_SERVICE_NAME, "platform-svc")));

        assertThat(resource.getAttribute(SERVICE_NAME)).isEqualTo("otel-svc");
    }

    @Test
    void platform_service_name_когда_otel_не_задан() {
        Resource resource = merge(new TestConfigProperties(Map.of(
                PlatformResourceProvider.PROP_SERVICE_NAME, "platform-svc")));

        assertThat(resource.getAttribute(SERVICE_NAME)).isEqualTo("platform-svc");
    }
}
