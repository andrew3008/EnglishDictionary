package space.br1440.platform.tracing.core.mdc.remote;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import space.br1440.platform.tracing.api.mdc.TracingMdcKeys;

import static org.assertj.core.api.Assertions.assertThat;

class RemoteServiceMdcTest {

    private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";

    @AfterEach
    void clearMdc() {
        MDC.clear();
        RemoteServiceMdc.clearForTrace(TRACE_ID);
    }

    @Test
    void putIfPresent_записывает_непустое_значение() {
        RemoteServiceMdc.putIfPresent("order-service");

        assertThat(MDC.get(TracingMdcKeys.REMOTE_SERVICE)).isEqualTo("order-service");
    }

    @Test
    void putIfPresent_игнорирует_null_и_blank() {
        RemoteServiceMdc.putIfPresent(null);
        RemoteServiceMdc.putIfPresent("  ");

        assertThat(MDC.get(TracingMdcKeys.REMOTE_SERVICE)).isNull();
    }

    @Test
    void clear_удаляет_ключ() {
        MDC.put(TracingMdcKeys.REMOTE_SERVICE, "billing");

        RemoteServiceMdc.clear();

        assertThat(MDC.get(TracingMdcKeys.REMOTE_SERVICE)).isNull();
    }

    @Test
    void clearForTrace_удаляет_mirror_по_traceId() {
        RemoteServiceMdc.putIfPresent("billing", TRACE_ID);

        RemoteServiceMdc.clearForTrace(TRACE_ID);

        assertThat(MDC.get(TracingMdcKeys.REMOTE_SERVICE)).isNull();
        assertThat(new RemoteServiceNameResolver(java.util.List.of()).resolve()).isEmpty();
    }
}
