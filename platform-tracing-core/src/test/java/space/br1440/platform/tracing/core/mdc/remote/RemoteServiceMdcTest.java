package space.br1440.platform.tracing.core.mdc.remote;

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
    void clearForTrace_очищает_mdc_и_mirror() {
        RemoteServiceMdc.putIfPresent("payment-service", "abc123");

        // mirror зафиксирован
        assertThat(RemoteServiceTraceMirror.get("abc123")).contains("payment-service");

        RemoteServiceMdc.clearForTrace("abc123");

        assertThat(MDC.get(TracingMdcKeys.REMOTE_SERVICE)).isNull();
        assertThat(RemoteServiceTraceMirror.get("abc123")).isEmpty();
    }
}
