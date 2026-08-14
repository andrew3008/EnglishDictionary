package space.br1440.platform.tracing.otel.javaagent.processor;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsUpdateStatus;

import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.otel.javaagent.control.AcceptedPolicyMutationAccess;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runtime-политика валидации (Фаза 14): атомарное переключение enabled/strict через accepted store.
 */
class ValidationPolicyRuntimeTest {

    @Test
    void updateValidation_атомарно_меняет_enabled_without_strict() {
        TracingSettingsSource access = TracingSettingsSource.isolatedValidation(true, false, true);
        ValidatingSpanProcessor processor = new ValidatingSpanProcessor(access);
        assertThat(processor.isEnabled()).isTrue();
        assertThat(processor.isStrict()).isFalse();
        long versionBefore = processor.getPolicyVersion();

        SettingsUpdateStatus outcome = AcceptedPolicyMutationAccess.applyValidation(access, false, false);

        assertThat(outcome).isEqualTo(SettingsUpdateStatus.APPLIED);
        assertThat(processor.isEnabled()).isFalse();
        assertThat(processor.isStrict()).isFalse();
        assertThat(processor.getPolicyVersion()).isEqualTo(versionBefore + 1);
    }
}
