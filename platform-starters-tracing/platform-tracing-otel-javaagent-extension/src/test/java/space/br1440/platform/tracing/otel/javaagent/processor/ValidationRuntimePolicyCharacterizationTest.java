package space.br1440.platform.tracing.otel.javaagent.processor;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsUpdateStatus;
import space.br1440.platform.tracing.otel.javaagent.control.settings.validation.SpanAttributeValidationLevel;

import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.otel.javaagent.control.AcceptedPolicyMutationAccess;

import static org.assertj.core.api.Assertions.assertThat;

class ValidationRuntimePolicyCharacterizationTest {

    @Test
    void updateValidation_increments_version_for_disabled_update() {
        // given
        TracingSettingsSource access = TracingSettingsSource.isolatedValidation(SpanAttributeValidationLevel.WARNING);
        try (ValidatingSpanProcessor processor = new ValidatingSpanProcessor(access)) {
            // when
            long v0 = processor.getPolicyVersion();

            // then
            assertThat(AcceptedPolicyMutationAccess.applyValidation(access, SpanAttributeValidationLevel.DISABLED))
                    .isEqualTo(SettingsUpdateStatus.APPLIED);
            assertThat(processor.validationLevel()).isEqualTo(SpanAttributeValidationLevel.DISABLED);
            assertThat(processor.getPolicyVersion()).isEqualTo(v0 + 1);
        }
    }
}
