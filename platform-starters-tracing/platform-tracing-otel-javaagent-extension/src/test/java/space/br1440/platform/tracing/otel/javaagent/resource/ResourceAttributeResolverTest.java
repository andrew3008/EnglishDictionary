package space.br1440.platform.tracing.otel.javaagent.resource;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.resources.Resource;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ResourceAttributeResolverTest {

    private final ResourceAttributeResolver resolver = new ResourceAttributeResolver();

    private static AttributeKey<String> key(String name) {
        return AttributeKey.stringKey(name);
    }

    private Attributes resolve(Map<String, String> config) {
        return resolver.resolve(new TestConfigProperties(config), Resource.empty(), null, null);
    }

    @Test
    void otel_service_name_omit() {
        Attributes attrs = resolve(Map.of(
                "otel.service.name", "otel-svc",
                PlatformResourceCustomizer.PROP_SERVICE_NAME, "platform-svc"));

        assertThat(attrs.get(key(PlatformAttributes.SERVICE_NAME))).isNull();
    }

    @Test
    void platform_service_name_when_otel_empty() {
        Attributes attrs = resolve(Map.of(PlatformResourceCustomizer.PROP_SERVICE_NAME, "platform-svc"));

        assertThat(attrs.get(key(PlatformAttributes.SERVICE_NAME))).isEqualTo("platform-svc");
    }

    @Test
    void spring_application_name_fallback() {
        Attributes attrs = resolve(Map.of("spring.application.name", "spring-svc"));

        assertThat(attrs.get(key(PlatformAttributes.SERVICE_NAME))).isEqualTo("spring-svc");
    }

    @Test
    void version_from_platform_config() {
        Attributes attrs = resolve(Map.of(PlatformResourceCustomizer.PROP_SERVICE_VERSION, "1.0"));

        assertThat(attrs.get(key(PlatformAttributes.SERVICE_VERSION))).isEqualTo("1.0");
    }

    @Test
    void version_from_build_info_when_platform_empty() {
        Attributes attrs = resolver.resolve(new TestConfigProperties(Map.of()), Resource.empty(), "2.0", null);

        assertThat(attrs.get(key(PlatformAttributes.SERVICE_VERSION))).isEqualTo("2.0");
    }

    @Test
    void environment_is_normalized() {
        Attributes attrs = resolve(Map.of(PlatformResourceCustomizer.PROP_ENVIRONMENT, "prod"));

        assertThat(attrs.get(key(PlatformAttributes.PLATFORM_ENVIRONMENT))).isEqualTo("production");
    }

    @Test
    void all_empty_writes_unknown_for_group_and_id() {
        Attributes attrs = resolve(Map.of());

        assertThat(attrs.get(key(PlatformAttributes.SERVICE_NAME))).isNull();
        assertThat(attrs.get(key(PlatformAttributes.PLATFORM_C_GROUP))).isEqualTo(EnvironmentNormalizer.UNKNOWN);
        assertThat(attrs.get(key(PlatformAttributes.PLATFORM_ID))).isEqualTo(EnvironmentNormalizer.UNKNOWN);
    }

    @Test
    void existing_non_default_service_name_omit() {
        Resource existing = Resource.create(Attributes.of(key(PlatformAttributes.SERVICE_NAME), "from-detector"));
        Attributes attrs = resolver.resolve(new TestConfigProperties(
                        Map.of(PlatformResourceCustomizer.PROP_SERVICE_NAME, "platform-svc")),
                existing, null, null);

        // existing уже содержит не-дефолтный service.name → platform омитит
        assertThat(attrs.get(key(PlatformAttributes.SERVICE_NAME))).isNull();
    }
}
