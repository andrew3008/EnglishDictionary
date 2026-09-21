package space.br1440.platform.tracing.otel.javaagent.resource;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.attributes.PlatformAttributes;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.resources.Resource;

class ResourceAttributeResolverTest {

    private static AttributeKey<String> key(String name) {
        return AttributeKey.stringKey(name);
    }

    private static Attributes resolve(Map<String, String> config) {
        return ResourceAttributeResolver.resolve(
                new TestConfigProperties(config), Resource.empty(), Resource.empty(), Optional::empty
        );
    }

    @Test
    void otelServiceNameOmit() {
        // given
        Resource otelEnv = Resource.create(Attributes.of(key(PlatformAttributes.SERVICE_NAME), "otel-svc"));

        // when
        Attributes attrs = ResourceAttributeResolver.resolve(
                new TestConfigProperties(Map.of(PlatformResourceCustomizer.PROP_SERVICE_NAME, "platform-svc")),
                Resource.empty(),
                otelEnv,
                Optional::empty
        );

        // then
        assertThat(attrs.get(key(PlatformAttributes.SERVICE_NAME))).isNull();
    }

    @Test
    void platformServiceNameWhenOtelEmpty() {
        // when
        Attributes attrs = resolve(Map.of(PlatformResourceCustomizer.PROP_SERVICE_NAME, "platform-svc"));

        // then
        assertThat(attrs.get(key(PlatformAttributes.SERVICE_NAME))).isEqualTo("platform-svc");
    }

    @Test
    void springApplicationNameFallback() {
        // when
        Attributes attrs = resolve(Map.of("spring.application.name", "spring-svc"));

        // then
        assertThat(attrs.get(key(PlatformAttributes.SERVICE_NAME))).isEqualTo("spring-svc");
    }

    @Test
    void versionFromPlatformConfig() {
        // when
        Attributes attrs = resolve(Map.of(PlatformResourceCustomizer.PROP_SERVICE_VERSION, "1.0"));

        // then
        assertThat(attrs.get(key(PlatformAttributes.SERVICE_VERSION))).isEqualTo("1.0");
    }

    @Test
    void versionFromBuildInfoWhenPlatformEmpty() {
        // when
        Attributes attrs = ResourceAttributeResolver.resolve(
                new TestConfigProperties(Map.of()),
                Resource.empty(),
                Resource.empty(),
                () -> Optional.of("2.0")
        );

        // then
        assertThat(attrs.get(key(PlatformAttributes.SERVICE_VERSION))).isEqualTo("2.0");
    }

    @Test
    void explicitVersionDoesNotReadBuildInfo() {
        // given
        AtomicBoolean read = new AtomicBoolean(false);

        // when
        Attributes attrs = ResourceAttributeResolver.resolve(
                new TestConfigProperties(Map.of(PlatformResourceCustomizer.PROP_SERVICE_VERSION, "9.9.9")),
                Resource.empty(),
                Resource.empty(),
                () -> {
                    read.set(true);
                    return Optional.of("2.0");
                });

        // then
        assertThat(attrs.get(key(PlatformAttributes.SERVICE_VERSION))).isEqualTo("9.9.9");
        assertThat(read).isFalse();
    }

    @Test
    void environmentIsNormalized() {
        // when
        Attributes attrs = resolve(Map.of(PlatformResourceCustomizer.PROP_ENVIRONMENT, "prod"));

        // then
        assertThat(attrs.get(key(PlatformAttributes.PLATFORM_ENVIRONMENT))).isEqualTo("production");
    }

    @Test
    void allEmptyWritesUnknownForId() {
        // when
        Attributes attrs = resolve(Map.of());

        // then
        assertThat(attrs.get(key(PlatformAttributes.SERVICE_NAME))).isNull();
        assertThat(attrs.get(key(PlatformAttributes.PLATFORM_ID))).isEqualTo(EnvironmentNormalizer.UNKNOWN);
    }

    @Test
    void existingNonDefaultServiceNameOmit() {
        // given
        Resource existing = Resource.create(Attributes.of(key(PlatformAttributes.SERVICE_NAME), "from-detector"));

        // when
        Attributes attrs = ResourceAttributeResolver.resolve(
                new TestConfigProperties(Map.of(PlatformResourceCustomizer.PROP_SERVICE_NAME, "platform-svc")),
                existing,
                Resource.empty(),
                Optional::empty
        );

        // then
        assertThat(attrs.get(key(PlatformAttributes.SERVICE_NAME))).isNull();
    }

    @Test
    void missingRequiredKeysWhenIdentityAbsent() {
        // when
        List<String> missing = ResourceAttributeResolver.missingRequiredKeys(
                Attributes.empty(), Resource.empty(), Resource.empty());

        // then
        assertThat(missing).containsExactly(
                PlatformAttributes.SERVICE_NAME,
                PlatformAttributes.PLATFORM_ENVIRONMENT);
    }

    @Test
    void otelEnvironmentServiceNameCoversRequiredKey() {
        // given
        Attributes resolved = Attributes.of(key(PlatformAttributes.PLATFORM_ENVIRONMENT), "production");
        Resource otelEnv = Resource.create(Attributes.of(key(PlatformAttributes.SERVICE_NAME), "otel-svc"));

        // when
        List<String> missing = ResourceAttributeResolver.missingRequiredKeys(resolved, otelEnv, Resource.empty());

        // then
        assertThat(missing).isEmpty();
    }

    @Test
    void unknownServiceIsTreatedAsAbsence() {
        // given
        Attributes resolved = Attributes.builder()
                .put(key(PlatformAttributes.SERVICE_NAME), "unknown_service:java")
                .put(key(PlatformAttributes.PLATFORM_ENVIRONMENT), "production")
                .build();

        // when
        List<String> missing = ResourceAttributeResolver.missingRequiredKeys(resolved, Resource.empty(), Resource.empty());

        // then
        assertThat(missing).containsExactly(PlatformAttributes.SERVICE_NAME);
    }
}
