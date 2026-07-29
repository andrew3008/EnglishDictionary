package space.br1440.platform.tracing.otel.javaagent.control.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class AcceptedPolicyAccessTest {

    private static volatile long allocationSink;

    @Test
    void allCategoryALeavesArePublishedThroughOneAcceptedEnvelope() {
        AcceptedPolicyAccess access = AcceptedPolicyAccess.isolatedSampling(
                true,
                List.of(),
                List.of(),
                Map.of("/initial", 0.1d),
                0.2d);
        AcceptedPolicyView initial = access.current();

        assertThat(access.updateSampling(false, 0.3d, Map.of("/orders", 0.4d)))
                .isEqualTo(AcceptedPolicyUpdateOutcome.APPLIED);
        assertThat(access.updateValidation(true, true))
                .isEqualTo(AcceptedPolicyUpdateOutcome.APPLIED);
        assertThat(access.updateExporter(false))
                .isEqualTo(AcceptedPolicyUpdateOutcome.APPLIED);
        assertThat(access.updateTraceControlHeaders(false))
                .isEqualTo(AcceptedPolicyUpdateOutcome.APPLIED);
        assertThat(access.updateDiagnosticsLogLevel("DEBUG"))
                .isEqualTo(AcceptedPolicyUpdateOutcome.APPLIED);

        AcceptedPolicyView accepted = access.current();
        assertThat(accepted.runtimeInstanceId()).isEqualTo(initial.runtimeInstanceId());
        assertThat(accepted.acceptedSequence()).isEqualTo(initial.acceptedSequence() + 5L);
        assertThat(accepted.samplingEnabled()).isFalse();
        assertThat(accepted.samplingRatio()).isEqualTo(0.3d);
        assertThat(accepted.samplingRouteRatios()).containsExactly(Map.entry("/orders", 0.4d));
        assertThat(accepted.validationEnabled()).isTrue();
        assertThat(accepted.validationStrict()).isTrue();
        assertThat(accepted.exporterEnabled()).isFalse();
        assertThat(accepted.traceControlHeadersEnabled()).isFalse();
        assertThat(accepted.diagnosticsLogLevel()).isEqualTo("DEBUG");
    }

    @Test
    void unchangedAndRejectedCandidatesDoNotPublishNewState() {
        AcceptedPolicyAccess access = AcceptedPolicyAccess.isolatedDefaults();
        AcceptedPolicyView initial = access.current();

        assertThat(access.updateExporter(initial.exporterEnabled()))
                .isEqualTo(AcceptedPolicyUpdateOutcome.UNCHANGED);
        assertThat(access.current()).isSameAs(initial);

        assertThat(access.updateSampling(
                initial.samplingEnabled(),
                initial.samplingRatio(),
                initial.samplingRouteRatios()))
                .isEqualTo(AcceptedPolicyUpdateOutcome.UNCHANGED);
        assertThat(access.current()).isSameAs(initial);

        assertThat(access.updateDiagnosticsLogLevel("unsupported"))
                .isEqualTo(AcceptedPolicyUpdateOutcome.REJECTED);
        assertThat(access.current()).isSameAs(initial);
    }

    @Test
    void routeRatiosRemainImmutableAfterPublication() {
        AcceptedPolicyAccess access = AcceptedPolicyAccess.isolatedSampling(
                true,
                List.of(),
                List.of(),
                Map.of("/orders", 0.4d),
                0.2d);

        assertThatThrownBy(() -> access.current().samplingRouteRatios().put("/payments", 0.5d))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void acceptedPolicyReadPathDoesNotAllocatePerReadOnHotSpot() {
        java.lang.management.ThreadMXBean standardBean = ManagementFactory.getThreadMXBean();
        Assumptions.assumeTrue(standardBean instanceof com.sun.management.ThreadMXBean);
        com.sun.management.ThreadMXBean allocationBean =
                (com.sun.management.ThreadMXBean) standardBean;
        Assumptions.assumeTrue(allocationBean.isThreadAllocatedMemorySupported());
        allocationBean.setThreadAllocatedMemoryEnabled(true);

        AcceptedPolicyAccess access = AcceptedPolicyAccess.isolatedDefaults();
        long sum = 0L;
        for (int index = 0; index < 200_000; index++) {
            AcceptedPolicyView policy = access.current();
            sum += policy.acceptedSequence();
            sum += Double.doubleToRawLongBits(policy.samplingRatio());
        }

        long threadId = Thread.currentThread().threadId();
        long before = allocationBean.getThreadAllocatedBytes(threadId);
        for (int index = 0; index < 2_000_000; index++) {
            AcceptedPolicyView policy = access.current();
            sum += policy.acceptedSequence();
            sum += Double.doubleToRawLongBits(policy.samplingRatio());
        }
        long allocated = allocationBean.getThreadAllocatedBytes(threadId) - before;
        allocationSink = sum;

        // ThreadMXBean может учитывать небольшую фиксированную стоимость самого измерения.
        // Допуск не зависит от числа чтений и по-прежнему выявляет создание объектов в hot path.
        assertThat(allocated).isLessThanOrEqualTo(1_024L);
    }
}
