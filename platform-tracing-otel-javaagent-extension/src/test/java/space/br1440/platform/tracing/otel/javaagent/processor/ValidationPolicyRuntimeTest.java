package space.br1440.platform.tracing.otel.javaagent.processor;

import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.otel.javaagent.control.state.AcceptedPolicyAccess;
import space.br1440.platform.tracing.otel.javaagent.control.state.AcceptedPolicyUpdateOutcome;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runtime-политика валидации (Фаза 14): атомарное переключение enabled/strict через accepted store.
 */
class ValidationPolicyRuntimeTest {

    @Test
    void updateValidation_атомарно_меняет_enabled_without_strict() {
        AcceptedPolicyAccess access = AcceptedPolicyAccess.isolatedValidation(true, false, true);
        ValidatingSpanProcessor processor = new ValidatingSpanProcessor(access);
        assertThat(processor.isEnabled()).isTrue();
        assertThat(processor.isStrict()).isFalse();
        long versionBefore = processor.getPolicyVersion();

        AcceptedPolicyUpdateOutcome outcome = access.updateValidation(false, false);

        assertThat(outcome).isEqualTo(AcceptedPolicyUpdateOutcome.APPLIED);
        assertThat(processor.isEnabled()).isFalse();
        assertThat(processor.isStrict()).isFalse();
        assertThat(processor.getPolicyVersion()).isEqualTo(versionBefore + 1);
    }
}
