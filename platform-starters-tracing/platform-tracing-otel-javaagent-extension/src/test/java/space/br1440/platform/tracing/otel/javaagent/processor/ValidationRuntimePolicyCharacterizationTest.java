package space.br1440.platform.tracing.otel.javaagent.processor;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsUpdateStatus;

import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.otel.javaagent.control.AcceptedPolicyMutationAccess;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Characterization runtime validation policy updates (PR-5B).
 */
class ValidationRuntimePolicyCharacterizationTest {

    @Test
    void updateValidation_increments_version_for_lenient_update() {
        TracingSettingsSource access = TracingSettingsSource.isolatedValidation(true, false, true);
        ValidatingSpanProcessor processor = new ValidatingSpanProcessor(access);
        long v0 = processor.getPolicyVersion();

        assertThat(AcceptedPolicyMutationAccess.applyValidation(access, false, false))
                .isEqualTo(SettingsUpdateStatus.APPLIED);
        assertThat(processor.isEnabled()).isFalse();
        assertThat(processor.isStrict()).isFalse();
        assertThat(processor.getPolicyVersion()).isEqualTo(v0 + 1);
    }
}
