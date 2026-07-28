package space.br1440.platform.tracing.autoconfigure.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.diagnostics.OtelAgentLogLevel;

class SpringActuatorTracingPolicyAdapterTest {

    private CompleteTracingPolicySnapshot initial;
    private CapturingClient client;
    private SpringActuatorTracingPolicyAdapter adapter;

    @BeforeEach
    void setUp() {
        initial = new CompleteTracingPolicySnapshot(
                true,
                0.1d,
                Map.of("/orders", 0.2d),
                true,
                false,
                true,
                true,
                OtelAgentLogLevel.INFO);
        client = new CapturingClient(accepted(initial));
        adapter = new SpringActuatorTracingPolicyAdapter(
                client,
                client,
                new TracingPolicyDeliveryDiagnostics());
    }

    @Test
    void everyTypedOperationChangesExactlyOneLeafAndSendsCompleteSnapshot() {
        assertOneLeafChange(
                () -> adapter.setSamplingEnabled(false),
                initial.withSamplingEnabled(false));
        assertOneLeafChange(
                () -> adapter.setSamplingRatio(0.7d),
                initial.withSamplingRatio(0.7d));
        assertOneLeafChange(
                () -> adapter.replaceSamplingRouteRatios(Map.of("/billing", 0.4d)),
                initial.withSamplingRouteRatios(Map.of("/billing", 0.4d)));
        assertOneLeafChange(
                () -> adapter.setValidationEnabled(false),
                initial.withValidationEnabled(false));
        assertOneLeafChange(
                () -> adapter.setValidationStrict(true),
                initial.withValidationStrict(true));
        assertOneLeafChange(
                () -> adapter.setExporterEnabled(false),
                initial.withExporterEnabled(false));
        assertOneLeafChange(
                () -> adapter.setTraceControlHeadersEnabled(false),
                initial.withTraceControlHeadersEnabled(false));
        assertOneLeafChange(
                () -> adapter.setDiagnosticsLogLevel(OtelAgentLogLevel.DEBUG),
                initial.withDiagnosticsLogLevel(OtelAgentLogLevel.DEBUG));
    }

    @Test
    void everyDeliveryOutcomeIsReturnedWithoutRetry() {
        for (TracingPolicyDeliveryOutcome outcome : TracingPolicyDeliveryOutcome.values()) {
            client.outcome = outcome;
            client.applyCount = 0;

            TracingPolicyDeliveryResult result = adapter.setSamplingRatio(0.5d);

            assertThat(result.outcome()).isEqualTo(outcome);
            assertThat(client.applyCount).isEqualTo(1);
        }
    }

    @Test
    void routeReplacementRejectsNullAndOversizedMapsBeforeDelivery() {
        org.assertj.core.api.Assertions.assertThatNullPointerException()
                .isThrownBy(() -> adapter.replaceSamplingRouteRatios(null));
        Map<String, Double> oversized = new java.util.LinkedHashMap<>();
        for (int index = 0; index <= CompleteTracingPolicySnapshot.MAX_ROUTE_ENTRIES; index++) {
            oversized.put("/route-" + index, 0.1d);
        }
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> adapter.replaceSamplingRouteRatios(oversized));
        assertThat(client.applyCount).isZero();
    }

    private void assertOneLeafChange(
            Supplier<TracingPolicyDeliveryResult> operation,
            CompleteTracingPolicySnapshot expected) {
        client.accepted = accepted(initial);
        client.candidate = null;
        operation.get();
        assertThat(client.candidate).isEqualTo(expected);
        assertThat(client.source).isEqualTo(TracingPolicySource.ACTUATOR);
        assertThat(client.trigger).isEqualTo(TracingPolicyApplyTrigger.ACTUATOR);
        assertThat(client.expected.acceptedSequence()).isEqualTo(7L);
    }

    private static AcceptedTracingPolicyView accepted(CompleteTracingPolicySnapshot policy) {
        return new AcceptedTracingPolicyView(
                UUID.randomUUID().toString(),
                7L,
                "a".repeat(64),
                policy,
                "APPLIED",
                UUID.randomUUID().toString(),
                "NONE",
                false,
                false);
    }

    private static final class CapturingClient
            implements AcceptedTracingPolicyReader, TracingPolicySnapshotClient {

        private AcceptedTracingPolicyView accepted;
        private CompleteTracingPolicySnapshot candidate;
        private ExpectedAcceptedState expected;
        private TracingPolicySource source;
        private TracingPolicyApplyTrigger trigger;
        private TracingPolicyDeliveryOutcome outcome = TracingPolicyDeliveryOutcome.APPLIED;
        private int applyCount;

        private CapturingClient(AcceptedTracingPolicyView accepted) {
            this.accepted = accepted;
        }

        @Override
        public AcceptedTracingPolicyView current() {
            return accepted;
        }

        @Override
        public TracingPolicyDeliveryResult applySnapshot(
                ExpectedAcceptedState expectedState,
                CompleteTracingPolicySnapshot snapshot,
                TracingPolicySource source,
                TracingPolicyApplyTrigger trigger,
                String sourceRevision) {
            applyCount++;
            this.expected = expectedState;
            this.candidate = snapshot;
            this.source = source;
            this.trigger = trigger;
            return new TracingPolicyDeliveryResult(outcome, "attempt", "NONE", 8L);
        }
    }
}
