package space.br1440.platform.tracing.test.harness;

import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.otel.propagation.control.PlatformTraceContextKeys;

import static org.assertj.core.api.Assertions.assertThat;

class SamplingContextFactoryTest {

    @Test
    void force_header_context_ставит_trace_control() {
        var ctx = SamplingContextFactory.withForceHeader("on");
        assertThat(ctx.get(PlatformTraceContextKeys.TRACE_CONTROL).forceTrace()).isTrue();
    }

    @Test
    void qa_context_ставит_qa_trace() {
        var ctx = SamplingContextFactory.withQaTrace();
        assertThat(ctx.get(PlatformTraceContextKeys.TRACE_CONTROL).qaTrace()).isTrue();
    }
}
