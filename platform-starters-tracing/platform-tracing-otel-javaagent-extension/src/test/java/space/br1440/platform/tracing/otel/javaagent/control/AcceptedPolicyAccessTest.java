package space.br1440.platform.tracing.otel.javaagent.control;

import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsUpdateStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.management.ManagementFactory;
import java.util.Map;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class AcceptedPolicyAccessTest {

    private static volatile long allocationSink;

    @Test
    void allCategoryALeavesArePublishedThroughOneAcceptedEnvelope() {
        TracingSettingsSource access = TracingSettingsSource.isolatedSampling(
                true,
                Map.of("/initial", 0.1d),
                0.2d);
        TracingSettingsRevision initial = access.current();

        assertThat(AcceptedPolicyMutationAccess.applySampling(access, false, 0.3d, Map.of("/orders", 0.4d)))
                .isEqualTo(SettingsUpdateStatus.APPLIED);
        assertThat(AcceptedPolicyMutationAccess.applyValidation(access, true, true))
                .isEqualTo(SettingsUpdateStatus.APPLIED);
        assertThat(AcceptedPolicyMutationAccess.applyExporter(access, false))
                .isEqualTo(SettingsUpdateStatus.APPLIED);
        assertThat(AcceptedPolicyMutationAccess.applyTraceControlHeaders(access, false))
                .isEqualTo(SettingsUpdateStatus.APPLIED);
        assertThat(access.applyLogLevel("DEBUG")).isTrue();

        TracingSettingsRevision accepted = access.current();
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
        TracingSettingsSource access = TracingSettingsSource.isolatedDefaults();
        TracingSettingsRevision initial = access.current();

        assertThat(AcceptedPolicyMutationAccess.applyExporter(access, initial.exporterEnabled()))
                .isEqualTo(SettingsUpdateStatus.UNCHANGED);
        assertThat(access.current()).isSameAs(initial);

        assertThat(AcceptedPolicyMutationAccess.applySampling(
                access,
                initial.samplingEnabled(),
                initial.samplingRatio(),
                initial.samplingRouteRatios()))
                .isEqualTo(SettingsUpdateStatus.UNCHANGED);
        assertThat(access.current()).isSameAs(initial);

        assertThat(access.applyLogLevel("unsupported")).isFalse();
        assertThat(access.current()).isSameAs(initial);
    }

    @Test
    void routeRatiosRemainImmutableAfterPublication() {
        TracingSettingsSource access = TracingSettingsSource.isolatedSampling(
                true,
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

        TracingSettingsSource access = TracingSettingsSource.isolatedDefaults();
        long sum = 0L;
        for (int index = 0; index < 200_000; index++) {
            TracingSettingsRevision policy = access.current();
            sum += policy.acceptedSequence();
            sum += Double.doubleToRawLongBits(policy.samplingRatio());
        }

        long threadId = Thread.currentThread().threadId();
        long before = allocationBean.getThreadAllocatedBytes(threadId);
        for (int index = 0; index < 2_000_000; index++) {
            TracingSettingsRevision policy = access.current();
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
