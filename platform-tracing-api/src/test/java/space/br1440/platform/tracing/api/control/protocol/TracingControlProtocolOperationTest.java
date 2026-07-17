package space.br1440.platform.tracing.api.control.protocol;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TracingControlProtocolOperationTest {

    @Test
    void enumIsSingleOperationSource() {
        assertThat(TracingControlProtocolOperation.APPLY_RUNTIME_POLICY.wireValue())
                .isEqualTo("APPLY_RUNTIME_POLICY");
        assertThat(TracingControlProtocolOperation.VALIDATE_RUNTIME_POLICY.wireValue())
                .isEqualTo("VALIDATE_RUNTIME_POLICY");
        assertThat(TracingControlProtocolOperation.READ_APPLIED_STATE.wireValue())
                .isEqualTo("READ_APPLIED_STATE");
        assertThat(TracingControlProtocolOperation.parse("UNKNOWN_CONTROL_OPERATION")).isEmpty();
    }
}
