package space.br1440.platform.tracing.otel.mdc.remote;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import space.br1440.platform.tracing.api.mdc.TracingMdcKeys;

import static org.assertj.core.api.Assertions.assertThat;

class RemoteServiceMdcTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void putIfPresent_writes_non_blank_value() {
        RemoteServiceMdc.putIfPresent("order-service");

        assertThat(MDC.get(TracingMdcKeys.REMOTE_SERVICE)).isEqualTo("order-service");
    }

    @Test
    void putIfPresent_ignores_null_and_blank() {
        RemoteServiceMdc.putIfPresent(null);
        RemoteServiceMdc.putIfPresent("  ");

        assertThat(MDC.get(TracingMdcKeys.REMOTE_SERVICE)).isNull();
    }

    @Test
    void clear_removes_key() {
        MDC.put(TracingMdcKeys.REMOTE_SERVICE, "billing");

        RemoteServiceMdc.clear();

        assertThat(MDC.get(TracingMdcKeys.REMOTE_SERVICE)).isNull();
    }

}
