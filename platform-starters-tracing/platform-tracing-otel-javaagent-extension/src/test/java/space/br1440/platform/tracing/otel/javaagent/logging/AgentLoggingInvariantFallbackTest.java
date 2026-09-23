package space.br1440.platform.tracing.otel.javaagent.logging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentLoggingInvariantFallbackTest {

    private final AgentLogCapture capture = new AgentLogCapture();
    private PlatformAgentLogger logger;

    @BeforeEach
    void installCapture() {
        capture.install();
        capture.applyLevel(AgentLogLevel.TRACE);
        logger = PlatformAgentLogger.get(AgentLoggingInvariantFallbackTest.class);
    }

    @AfterEach
    void restore() {
        capture.restore();
    }

    @Test
    void hostile_inputs_do_not_report_an_invariant_violation() {
        // given
        Object[] tooManyFields = new Object[AgentLogFieldNamePolicy.MAX_FIELDS * 2 + 2];
        for (int i = 0; i < tooManyFields.length; i += 2) {
            tooManyFields[i] = "f" + (i / 2);
            tooManyFields[i + 1] = "v";
        }

        // when
        EmitResult surrogate = logger.warn(AgentEventCode.CTRL_UPDATE, "bad\uD800text");
        EmitResult bidi = logger.warn(AgentEventCode.CTRL_UPDATE, "bad\u202Etext");
        EmitResult huge = logger.warn(AgentEventCode.CTRL_UPDATE, "x".repeat(10_000));
        EmitResult missing = logger.warn(AgentEventCode.CTRL_UPDATE, null);
        EmitResult overflow = logger.warn(AgentEventCode.CTRL_UPDATE, "Tracing settings update failed", tooManyFields);
        EmitResult duplicate = logger.warn(AgentEventCode.CTRL_UPDATE, "Tracing settings update failed",
                "kind", "a", "Kind", "b");

        // then
        assertThat(missing).isEqualTo(EmitResult.REJECTED_BY_SECURITY_POLICY);
        assertThat(overflow).isEqualTo(EmitResult.REJECTED_BY_SECURITY_POLICY);
        assertThat(duplicate).isEqualTo(EmitResult.REJECTED_BY_SECURITY_POLICY);
        assertThat(surrogate).isIn(EmitResult.EMITTED, EmitResult.REJECTED_BY_SECURITY_POLICY, EmitResult.DELIVERY_FAILED);
        assertThat(bidi).isIn(EmitResult.EMITTED, EmitResult.REJECTED_BY_SECURITY_POLICY, EmitResult.DELIVERY_FAILED);
        assertThat(huge).isIn(EmitResult.EMITTED, EmitResult.REJECTED_BY_SECURITY_POLICY, EmitResult.DELIVERY_FAILED);
        assertThat(capture.rendered()).doesNotContain("invariant_violation");
        assertThat(capture.events())
                .filteredOn(event -> event.eventCode() == AgentEventCode.ENCODE_FALLBACK)
                .allSatisfy(event -> assertThat(event.fields())
                        .containsEntry("fallback_reason", "byte_budget")
                        .containsEntry("original_event_code", AgentEventCode.CTRL_UPDATE.code()));
    }
}
