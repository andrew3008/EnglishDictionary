package space.br1440.platform.tracing.otel.extension.resource;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.resources.Resource;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.otel.extension.configuration.ExtensionDefaults;
import space.br1440.platform.tracing.otel.extension.configuration.ExtensionPropertyNames;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ResourceAttributeResolverTest {

    private final ResourceAttributeResolver resolver = new ResourceAttributeResolver();

    private static AttributeKey<String> key(String name) {
        return AttributeKey.stringKey(name);
    }

    private Attributes resolve(Map<String, String> config) {
        return resolver.resolve(new TestConfigProperties(config), Resource.empty(),
                Optional.empty(), Optional.empty());
    }

    @Test
    void otel_service_name_omit() {
        Attributes attrs = resolve(Map.of(
                "otel.service.name", "otel-svc",
                PlatformResourceProvider.PROP_SERVICE_NAME, "platform-svc"));

        assertThat(attrs.get(key(PlatformAttributes.SERVICE_NAME))).isNull();
    }

    @Test
    void platform_service_name_когда_otel_пуст() {
        Attributes attrs = resolve(Map.of(PlatformResourceProvider.PROP_SERVICE_NAME, "platform-svc"));

        assertThat(attrs.get(key(PlatformAttributes.SERVICE_NAME))).isEqualTo("platform-svc");
    }

    @Test
    void spring_application_name_fallback() {
        Attributes attrs = resolve(Map.of("spring.application.name", "spring-svc"));

        assertThat(attrs.get(key(PlatformAttributes.SERVICE_NAME))).isEqualTo("spring-svc");
    }

    @Test
    void version_из_platform_config() {
        Attributes attrs = resolve(Map.of(PlatformResourceProvider.PROP_SERVICE_VERSION, "1.0"));

        assertThat(attrs.get(key(PlatformAttributes.SERVICE_VERSION))).isEqualTo("1.0");
    }

    @Test
    void version_из_build_info_когда_platform_пуст() {
        Attributes attrs = resolver.resolve(new TestConfigProperties(Map.of()), Resource.empty(),
                Optional.of("2.0"), Optional.empty());

        assertThat(attrs.get(key(PlatformAttributes.SERVICE_VERSION))).isEqualTo("2.0");
    }

    @Test
    void environment_нормализуется() {
        Attributes attrs = resolve(Map.of(PlatformResourceProvider.PROP_ENVIRONMENT, "prod"));

        assertThat(attrs.get(key(PlatformAttributes.PLATFORM_ENVIRONMENT))).isEqualTo("production");
    }

    @Test
    void все_пусто_c_group_id_unknown_policy_default() {
        Attributes attrs = resolve(Map.of());

        assertThat(attrs.get(key(PlatformAttributes.SERVICE_NAME))).isNull();
        assertThat(attrs.get(key(PlatformAttributes.PLATFORM_C_GROUP))).isEqualTo(EnvironmentNormalizer.UNKNOWN);
        assertThat(attrs.get(key(PlatformAttributes.PLATFORM_ID))).isEqualTo(EnvironmentNormalizer.UNKNOWN);
        assertThat(attrs.get(key(ExtensionPropertyNames.RESOURCE_POLICY_VERSION_ATTR)))
                .isEqualTo(ExtensionDefaults.DEFAULT_RESOURCE_POLICY_VERSION);
    }

    @Test
    void existing_non_default_service_name_omit() {
        Resource existing = Resource.create(Attributes.of(key(PlatformAttributes.SERVICE_NAME), "from-detector"));
        Attributes attrs = resolver.resolve(new TestConfigProperties(
                        Map.of(PlatformResourceProvider.PROP_SERVICE_NAME, "platform-svc")),
                existing, Optional.empty(), Optional.empty());

        // existing уже содержит не-дефолтный service.name → platform омитит
        assertThat(attrs.get(key(PlatformAttributes.SERVICE_NAME))).isNull();
    }
}
