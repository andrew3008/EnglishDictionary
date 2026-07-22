package space.br1440.platform.tracing.otel.extension.processor;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runtime-политика валидации (Фаза 14): атомарное переключение enabled/strict через снимок.
 */
class ValidationPolicyRuntimeTest {

    @Test
    void updateValidationPolicy_атомарно_меняет_enabled_without_strict() {
        ValidatingSpanProcessor processor = new ValidatingSpanProcessor(false);
        assertThat(processor.isEnabled()).isTrue();
        assertThat(processor.isStrict()).isFalse();
        long versionBefore = processor.getPolicyVersion();

        boolean applied = processor.updateValidationPolicy(false, false);

        assertThat(applied).isTrue();
        assertThat(processor.isEnabled()).isFalse();
        assertThat(processor.isStrict()).isFalse();
        assertThat(processor.getPolicyVersion()).isEqualTo(versionBefore + 1);
    }
}
