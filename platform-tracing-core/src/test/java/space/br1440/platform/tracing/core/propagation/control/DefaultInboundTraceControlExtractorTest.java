package space.br1440.platform.tracing.core.propagation.control;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.attributes.PlatformSamplingReasons;
import space.br1440.platform.tracing.api.propagation.control.InboundTraceControl;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DefaultInboundTraceControlExtractor: парсинг inbound заголовков")
class DefaultInboundTraceControlExtractorTest {

    private final DefaultInboundTraceControlExtractor extractor = new DefaultInboundTraceControlExtractor();

    @Test
    @DisplayName("force trace on -> forceTrace=true, FORCE_HEADER reason")
    void forceTraceOn() {
        InboundTraceControl control = extractor.fromHeaders("on", null, null);
        assertThat(control.forceTrace()).isTrue();
        assertThat(control.qaTrace()).isFalse();
        assertThat(control.samplingReason()).isEqualTo(PlatformSamplingReasons.FORCE_HEADER);
        assertThat(control.rawForceTraceValue()).isEqualTo("on");
    }

    @Test
    @DisplayName("qa trace -> qaTrace=true, QA_TRACE reason")
    void qaTrace() {
        InboundTraceControl control = extractor.fromHeaders(null, "1", null);
        assertThat(control.forceTrace()).isFalse();
        assertThat(control.qaTrace()).isTrue();
        assertThat(control.samplingReason()).isEqualTo(PlatformSamplingReasons.QA_TRACE);
    }

    @Test
    @DisplayName("force + qa одновременно -> FORCE_HEADER имеет приоритет над QA_TRACE")
    void forceTracePriorityOverQaTrace() {
        InboundTraceControl control = extractor.fromHeaders("on", "1", null);
        assertThat(control.forceTrace()).isTrue();
        assertThat(control.qaTrace()).isTrue();
        assertThat(control.samplingReason()).isEqualTo(PlatformSamplingReasons.FORCE_HEADER);
    }

    @Test
    @DisplayName("invalid request id -> null requestId")
    void invalidRequestIdRejected() {
        InboundTraceControl control = extractor.fromHeaders(null, null, "bad\r\nid");
        assertThat(control.requestId()).isNull();
    }

    @Test
    @DisplayName("все null -> пустой контроль без reason")
    void allNullHeaders() {
        InboundTraceControl control = extractor.fromHeaders(null, null, null);
        assertThat(control.forceTrace()).isFalse();
        assertThat(control.qaTrace()).isFalse();
        assertThat(control.requestId()).isNull();
        assertThat(control.samplingReason()).isNull();
    }
}
