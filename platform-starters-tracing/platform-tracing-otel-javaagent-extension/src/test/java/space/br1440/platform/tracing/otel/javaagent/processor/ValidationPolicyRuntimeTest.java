package space.br1440.platform.tracing.otel.javaagent.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSourceTestFixtures.boundDefaults;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.control.AcceptedPolicyMutationAccess;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsUpdateStatus;
import space.br1440.platform.tracing.otel.javaagent.control.settings.validation.SpanAttributeValidationLevel;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;

class ValidationPolicyRuntimeTest {

    @Test
    void updateValidation_atomically_changes_level_to_disabled() {
        // given
        TracingSettingsSource access = boundDefaults(AgentInstanceId.random());
        try (ValidatingSpanProcessor processor = new ValidatingSpanProcessor(access)) {
            // then
            assertThat(processor.validationLevel()).isEqualTo(SpanAttributeValidationLevel.WARNING);
            long versionBefore = processor.getPolicyVersion();

            SettingsUpdateStatus outcome = AcceptedPolicyMutationAccess.applyValidation(
                    access,
                    SpanAttributeValidationLevel.DISABLED
            );

            assertThat(outcome).isEqualTo(SettingsUpdateStatus.APPLIED);
            assertThat(processor.validationLevel()).isEqualTo(SpanAttributeValidationLevel.DISABLED);
            assertThat(processor.getPolicyVersion()).isEqualTo(versionBefore + 1);
        }
    }
}
