package space.br1440.platform.tracing.autoconfigure.identity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import space.br1440.platform.tracing.api.mdc.TracingMdcKeys;
import space.br1440.platform.tracing.otel.mdc.remote.RemoteServiceTraceContext;

class RemoteServiceMdcBoundarySupportTest {

    private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";

    @AfterEach
    void cleanup() {
        RemoteServiceMdcBoundarySupport.clear(TRACE_ID);
        MDC.clear();
    }

    @Test
    void project_and_clear_separate_MDC_and_trace_context_owners() {
        RemoteServiceTraceContext.record(TRACE_ID, "billing");

        RemoteServiceMdcBoundarySupport.project(RemoteServiceTraceContext.find(TRACE_ID).orElse(null));

        assertThat(MDC.get(TracingMdcKeys.REMOTE_SERVICE)).isEqualTo("billing");
        assertThat(RemoteServiceTraceContext.find(TRACE_ID)).contains("billing");

        RemoteServiceMdcBoundarySupport.clear(TRACE_ID);

        assertThat(MDC.get(TracingMdcKeys.REMOTE_SERVICE)).isNull();
        assertThat(RemoteServiceTraceContext.find(TRACE_ID)).isEmpty();
    }

    @Test
    void boundary_reuse_does_not_see_previous_value() {
        RemoteServiceTraceContext.record(TRACE_ID, "first");
        RemoteServiceMdcBoundarySupport.project("first");
        RemoteServiceMdcBoundarySupport.clear(TRACE_ID);

        assertThat(MDC.get(TracingMdcKeys.REMOTE_SERVICE)).isNull();
        assertThat(RemoteServiceTraceContext.find(TRACE_ID)).isEmpty();
    }
}
