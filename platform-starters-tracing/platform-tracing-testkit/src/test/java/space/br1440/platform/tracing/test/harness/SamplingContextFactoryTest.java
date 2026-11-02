package space.br1440.platform.tracing.test.harness;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.propagation.control.InboundTraceControl;
import space.br1440.platform.tracing.runtime.propagation.control.PlatformTraceContextKeys;

import io.opentelemetry.context.Context;

class SamplingContextFactoryTest {

    @Test
    void force_header_context_sets_trace_control() {
        // given
        Context ctx = SamplingContextFactory.withForceHeader("on");

        // when
        InboundTraceControl control = ctx.get(PlatformTraceContextKeys.TRACE_CONTROL);

        // then
        assertThat(control).isNotNull();
        assertThat(control.forceTrace()).isTrue();
    }

    @Test
    void qa_context_sets_qa_trace() {
        // given
        Context ctx = SamplingContextFactory.withQaTrace();

        // when
        InboundTraceControl control = ctx.get(PlatformTraceContextKeys.TRACE_CONTROL);

        // then
        assertThat(control).isNotNull();
        assertThat(control.qaTrace()).isTrue();
    }
}
