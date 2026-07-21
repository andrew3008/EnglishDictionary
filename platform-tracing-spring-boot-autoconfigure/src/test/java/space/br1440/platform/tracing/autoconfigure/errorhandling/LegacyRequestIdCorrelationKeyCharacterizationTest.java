package space.br1440.platform.tracing.autoconfigure.errorhandling;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import space.br1440.platform.tracing.api.mdc.TracingMdcKeys;

class LegacyRequestIdCorrelationKeyCharacterizationTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void legacyMdcKeyIsNotAnIdentityReadPath() {
        MDC.put("correlation_id", "legacy-request-42");

        var snapshot = new RequestTraceContextSnapshotSupplier(() -> null).get();

        assertThat(TracingMdcKeys.REQUEST_ID).isEqualTo("requestId");
        assertThat(TracingMdcKeys.CORRELATION_ID).isEqualTo("correlationId");
        assertThat(snapshot.requestId()).isNull();
        assertThat(snapshot.correlationId()).isNull();
    }
}
