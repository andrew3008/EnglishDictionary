package space.br1440.platform.tracing.otel.mdc.remote;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RemoteServiceTraceContextTest {

    private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";

    @AfterEach
    void clearContext() {
        RemoteServiceTraceContext.clear(TRACE_ID);
    }

    @Test
    void recordFindClear_образуют_узкий_trace_scoped_lifecycle() {
        RemoteServiceTraceContext.record(TRACE_ID, "billing");

        assertThat(RemoteServiceTraceContext.find(TRACE_ID)).contains("billing");

        RemoteServiceTraceContext.clear(TRACE_ID);

        assertThat(RemoteServiceTraceContext.find(TRACE_ID)).isEmpty();
    }

    @Test
    void повторное_использование_traceId_не_возвращает_предыдущее_значение() {
        RemoteServiceTraceContext.record(TRACE_ID, "first");
        RemoteServiceTraceContext.clear(TRACE_ID);
        RemoteServiceTraceContext.record(TRACE_ID, "second");

        assertThat(RemoteServiceTraceContext.find(TRACE_ID)).contains("second");
    }
}
