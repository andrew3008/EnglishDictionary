package space.br1440.platform.tracing.otel.javaagent.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.management.ManagementFactory;
import java.util.Map;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsUpdateStatus;
import space.br1440.platform.tracing.otel.javaagent.control.settings.validation.SpanAttributeValidationLevel;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;

class AcceptedPolicyAccessTest {

    @Test
    void allCategoryALeavesArePublishedThroughOneAcceptedEnvelope() {
        // given
        TracingSettingsSource access = TracingSettingsSourceTestFixtures.boundSampling(
                AgentInstanceId.random(),
                true,
                Map.of("/initial", 0.1d),
                0.2d);
        TracingSettingsRevision initial = access.current();

        // when/then
        assertThat(AcceptedPolicyMutationAccess.applySampling(access, false, 0.3d, Map.of("/orders", 0.4d)))
                .isEqualTo(SettingsUpdateStatus.APPLIED);
        assertThat(AcceptedPolicyMutationAccess.applyValidation(access, SpanAttributeValidationLevel.ERROR))
                .isEqualTo(SettingsUpdateStatus.APPLIED);
        assertThat(AcceptedPolicyMutationAccess.applyExporter(access, false))
                .isEqualTo(SettingsUpdateStatus.APPLIED);
        assertThat(AcceptedPolicyMutationAccess.applyTraceControlHeaders(access, false))
                .isEqualTo(SettingsUpdateStatus.APPLIED);
        assertThat(AcceptedPolicyMutationAccess.applyDiagnosticsLogLevel(access, "DEBUG")).isTrue();

        // then
        TracingSettingsRevision accepted = access.current();
        assertThat(accepted.agentInstanceId()).isEqualTo(initial.agentInstanceId());
        assertThat(accepted.acceptedSequence()).isEqualTo(initial.acceptedSequence() + 5L);
        assertThat(accepted.samplingEnabled()).isFalse();
        assertThat(accepted.samplingRatio()).isEqualTo(0.3d);
        assertThat(accepted.samplingRouteRatios()).containsExactly(Map.entry("/orders", 0.4d));
        assertThat(accepted.validationLevel()).isEqualTo(SpanAttributeValidationLevel.ERROR);
        assertThat(accepted.exporterEnabled()).isFalse();
        assertThat(accepted.traceControlHeadersEnabled()).isFalse();
        assertThat(accepted.diagnosticsLogLevel()).isEqualTo("DEBUG");
    }

    @Test
    void unchangedAndRejectedCandidatesDoNotPublishNewState() {
        // given
        TracingSettingsSource access = TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random());
        TracingSettingsRevision initial = access.current();

        // when/then
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

        assertThat(AcceptedPolicyMutationAccess.applyDiagnosticsLogLevel(access, "unsupported")).isFalse();
        assertThat(access.current()).isSameAs(initial);
    }

    @Test
    void routeRatiosRemainImmutableAfterPublication() {
        // given
        TracingSettingsSource access = TracingSettingsSourceTestFixtures.boundSampling(AgentInstanceId.random(),
                true,
                Map.of("/orders", 0.4d),
                0.2d);

        // when/then
        assertThatThrownBy(() -> access.current().samplingRouteRatios().put("/payments", 0.5d))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void acceptedPolicyReadPathDoesNotAllocatePerReadOnHotSpot() {
        // given
        java.lang.management.ThreadMXBean standardBean = ManagementFactory.getThreadMXBean();
        Assumptions.assumeTrue(standardBean instanceof com.sun.management.ThreadMXBean);
        com.sun.management.ThreadMXBean allocationBean =
                (com.sun.management.ThreadMXBean) standardBean;
        Assumptions.assumeTrue(allocationBean.isThreadAllocatedMemorySupported());
        allocationBean.setThreadAllocatedMemoryEnabled(true);

        TracingSettingsSource access = TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random());
        int warmupIterations = 200_000;
        int measuredIterations = 2_000_000;
        long sum = 0L;
        for (int index = 0; index < warmupIterations; index++) {
            TracingSettingsRevision policy = access.current();
            sum += policy.acceptedSequence();
            sum += Double.doubleToRawLongBits(policy.samplingRatio());
        }

        // when
        long threadId = Thread.currentThread().threadId();
        long before = allocationBean.getThreadAllocatedBytes(threadId);
        for (int index = 0; index < measuredIterations; index++) {
            TracingSettingsRevision policy = access.current();
            sum += policy.acceptedSequence();
            sum += Double.doubleToRawLongBits(policy.samplingRatio());
        }

        long allocated = allocationBean.getThreadAllocatedBytes(threadId) - before;
        TracingSettingsRevision after = access.current();
        long expectedSum = (long) (warmupIterations + measuredIterations)
                * (after.acceptedSequence() + Double.doubleToRawLongBits(after.samplingRatio()));

        // then
        assertThat(sum)
                .as("сумма обязана остаться живой, иначе JIT может выкинуть read-loop")
                .isEqualTo(expectedSum);
        assertThat(allocated).isLessThanOrEqualTo(1_024L);
    }
}
