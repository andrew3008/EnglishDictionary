package space.br1440.platform.tracing.otel.javaagent.processor;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsUpdateStatus;

import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.otel.javaagent.control.AcceptedPolicyMutationAccess;

import static org.assertj.core.api.Assertions.assertThat;

class ValidationPolicyRuntimeTest {

    @Test
    void updateValidation_atomically_changes_enabled_without_strict() {
        // given
        TracingSettingsSource access = TracingSettingsSource.isolatedValidation(true, false);
        try (ValidatingSpanProcessor processor = new ValidatingSpanProcessor(access)) {
        // then
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
}
