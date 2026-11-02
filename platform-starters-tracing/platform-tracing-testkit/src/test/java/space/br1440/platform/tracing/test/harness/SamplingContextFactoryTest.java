package space.br1440.platform.tracing.test.harness;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.opentelemetry.context.Context;

import space.br1440.platform.tracing.runtime.propagation.control.PlatformTraceContextKeys;

class SamplingContextFactoryTest {

    @Test
    void force_header_context_sets_trace_control() {
        Context ctx = SamplingContextFactory.withForceHeader("on");
        assertThat(ctx.get(PlatformTraceContextKeys.TRACE_CONTROL).forceTrace()).isTrue();
    }

    @Test
    void qa_context_sets_qa_trace() {
        Context ctx = SamplingContextFactory.withQaTrace();
        assertThat(ctx.get(PlatformTraceContextKeys.TRACE_CONTROL).qaTrace()).isTrue();
    }
}
