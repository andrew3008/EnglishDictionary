package space.br1440.platform.tracing.autoconfigure.errorhandling;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import space.br1440.platform.tracing.api.mdc.TracingMdcKeys;
import space.br1440.platform.tracing.test.characterization.KnownDefect;
import space.br1440.platform.tracing.test.characterization.KnownDefectId;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyRequestIdCorrelationKeyCharacterizationTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @KnownDefect(KnownDefectId.LEGACY_REQUEST_ID_CORRELATION_KEY)
    void snapshotReadsRequestIdentityFromLegacyCorrelationKey() {
        MDC.put(TracingMdcKeys.CORRELATION_ID, "request-42");

        var snapshot = new RequestTraceContextSnapshotSupplier().get();

        assertThat(TracingMdcKeys.CORRELATION_ID).isEqualTo("correlation_id");
        assertThat(snapshot.correlationId()).isEqualTo("request-42");
    }
}
