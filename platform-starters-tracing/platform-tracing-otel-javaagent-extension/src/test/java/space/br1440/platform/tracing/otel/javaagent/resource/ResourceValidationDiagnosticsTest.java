package space.br1440.platform.tracing.otel.javaagent.resource;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.resources.Resource;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResourceValidationDiagnosticsTest {

    private final ResourceValidationDiagnostics diagnostics = new ResourceValidationDiagnostics();

    private static AttributeKey<String> key(String name) {
        return AttributeKey.stringKey(name);
    }

    private static Attributes fullIdentity() {
        return Attributes.builder()
                .put(key(PlatformAttributes.SERVICE_NAME), "demo")
                .put(key(PlatformAttributes.PLATFORM_ENVIRONMENT), "production")
                .build();
    }

    @Test
    void passed_when_all_required_present() {
        // given
        // when
        ResourceValidationDiagnostics.ValidationResult result = diagnostics.validate(
                fullIdentity(), Resource.empty(), Resource.empty(), ResourceValidationMode.ERROR
        );

        // then
        assertThat(result.passed()).isTrue();
        assertThat(result.missingKeys()).isEmpty();
    }

    @Test
    void strict_throw_when_service_name_absent() {
        // given
        Attributes resolved = Attributes.builder()
                .put(key(PlatformAttributes.PLATFORM_ENVIRONMENT), "production")
                .build();

        // when
        ResourceValidationDiagnostics.ValidationResult result = diagnostics.validate(
                resolved, Resource.empty(), Resource.empty(), ResourceValidationMode.ERROR
        );

        // then
        assertThat(result.passed()).isFalse();
        assertThat(result.missingKeys()).contains(PlatformAttributes.SERVICE_NAME);
        assertThatThrownBy(() -> diagnostics.applyOrThrow(result))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void effective_view_otelEnv_covers_service_name() {
        // given
        Attributes resolved = Attributes.builder()
                .put(key(PlatformAttributes.PLATFORM_ENVIRONMENT), "production")
                .build();
        Resource otelEnv = Resource.create(Attributes.of(key(PlatformAttributes.SERVICE_NAME), "otel-svc"));

        // when
        ResourceValidationDiagnostics.ValidationResult result = diagnostics.validate(
                resolved, otelEnv, Resource.empty(), ResourceValidationMode.ERROR
        );

        // then
        assertThat(result.passed()).isTrue();
    }

    @Test
    void unknown_service_treated_as_absence() {
        // given
        Attributes resolved = Attributes.builder()
                .put(key(PlatformAttributes.SERVICE_NAME), "unknown_service:java")
                .put(key(PlatformAttributes.PLATFORM_ENVIRONMENT), "production")
                .build();

        // when
        ResourceValidationDiagnostics.ValidationResult result = diagnostics.validate(
                resolved, Resource.empty(), Resource.empty(), ResourceValidationMode.ERROR
        );

        // then
        assertThat(result.passed()).isFalse();
        assertThat(result.missingKeys()).contains(PlatformAttributes.SERVICE_NAME);
    }

    @Test
    void lenient_does_not_throw() {
        // given
        // when
        ResourceValidationDiagnostics.ValidationResult result = diagnostics.validate(
                Attributes.empty(), Resource.empty(), Resource.empty(), ResourceValidationMode.WARNING
        );

        // then
        assertThat(result.passed()).isFalse();
        diagnostics.applyOrThrow(result);
    }
}
