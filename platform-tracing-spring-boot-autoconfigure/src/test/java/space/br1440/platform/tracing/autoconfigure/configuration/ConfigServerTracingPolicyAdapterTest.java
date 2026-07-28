package space.br1440.platform.tracing.autoconfigure.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.OriginTrackedMapPropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import space.br1440.platform.tracing.autoconfigure.diagnostics.OtelAgentLogLevel;

class ConfigServerTracingPolicyAdapterTest {

    @Test
    void completeManifestContainsExactlyEightCategoryALeaves() {
        ConfigServerPolicyManifestBuilder.CompleteManifest manifest =
                manifestBuilder().build(environment(true));

        CompleteTracingPolicySnapshot policy = manifest.policy();
        assertThat(policy.samplingEnabled()).isTrue();
        assertThat(policy.samplingRatio()).isEqualTo(0.25d);
        assertThat(policy.samplingRouteRatios()).containsExactlyEntriesOf(Map.of("/orders", 0.4d));
        assertThat(policy.validationEnabled()).isTrue();
        assertThat(policy.validationStrict()).isFalse();
        assertThat(policy.exporterEnabled()).isTrue();
        assertThat(policy.traceControlHeadersEnabled()).isTrue();
        assertThat(policy.diagnosticsLogLevel()).isEqualTo(OtelAgentLogLevel.INFO);
    }

    @Test
    void refreshPerformsOneFullRetryOnlyAfterStale() {
        FakeClient client = new FakeClient();
        client.firstOutcome = TracingPolicyDeliveryOutcome.STALE;
        ConfigServerTracingPolicyAdapter adapter = adapter(environment(true), client);

        TracingPolicyDeliveryResult result = adapter.refresh();

        assertThat(result.outcome()).isEqualTo(TracingPolicyDeliveryOutcome.APPLIED);
        assertThat(client.applyCount).isEqualTo(2);
        assertThat(client.readCount).isEqualTo(2);
    }

    @Test
    void rejectedAndFailedDeliveryAreNotRetried() {
        for (TracingPolicyDeliveryOutcome outcome : java.util.List.of(
                TracingPolicyDeliveryOutcome.REJECTED,
                TracingPolicyDeliveryOutcome.FAILED)) {
            FakeClient client = new FakeClient();
            client.firstOutcome = outcome;
            ConfigServerTracingPolicyAdapter adapter = adapter(environment(true), client);

            TracingPolicyDeliveryResult result = adapter.refresh();

            assertThat(result.outcome()).isEqualTo(outcome);
            assertThat(client.applyCount).isEqualTo(1);
            assertThat(client.readCount).isEqualTo(1);
        }
    }

    @Test
    void rejectedManifestDoesNotUseLkgAndDoesNotInvokeWriter() {
        FakeClient client = new FakeClient();
        ConfigServerTracingPolicyAdapter adapter = adapter(environment(false), client);

        TracingPolicyDeliveryResult result = adapter.refresh();

        assertThat(result.outcome()).isEqualTo(TracingPolicyDeliveryOutcome.REJECTED);
        assertThat(client.applyCount).isZero();
    }

    @Test
    void incompleteInitialManifestBlocksApplicationReadiness() {
        FakeClient client = new FakeClient();
        TracingConfigurationReadiness readiness = new TracingConfigurationReadiness();
        ConfigServerTracingPolicyAdapter adapter = new ConfigServerTracingPolicyAdapter(
                environment(false),
                manifestBuilder(),
                client,
                client,
                readiness,
                new TracingPolicyDeliveryDiagnostics(),
                Runnable::run);

        TracingPolicyDeliveryResult result = adapter.applyInitial();

        assertThat(result.outcome()).isEqualTo(TracingPolicyDeliveryOutcome.REJECTED);
        assertThat(readiness.current().state())
                .isEqualTo(TracingConfigurationReadiness.State.BLOCKED);
        assertThat(client.applyCount).isZero();
    }

    @Test
    void optionalConfigServerImportIsRejected() {
        FakeClient client = new FakeClient();
        StandardEnvironment environment = environment(true);
        environment.getPropertySources().addFirst(new MapPropertySource(
                "optionalConfigImport",
                Map.of(ConfigServerPolicyManifestBuilder.CONFIG_IMPORT, "optional:configserver:")));
        ConfigServerTracingPolicyAdapter adapter = adapter(environment, client);

        TracingPolicyDeliveryResult result = adapter.applyInitial();

        assertThat(result.outcome()).isEqualTo(TracingPolicyDeliveryOutcome.REJECTED);
        assertThat(client.applyCount).isZero();
    }

    @Test
    void repeatedRefreshSignalsUseOneWorkerAndPreserveFinalGeneration() {
        java.util.List<Runnable> tasks = new java.util.ArrayList<>();
        AtomicInteger runs = new AtomicInteger();
        TracingPolicyRefreshCoalescer coalescer = new TracingPolicyRefreshCoalescer(
                tasks::add,
                generation -> runs.incrementAndGet(),
                (generation, failure) -> {
                    throw failure;
                });

        coalescer.signalRefresh();
        coalescer.signalRefresh();
        coalescer.signalRefresh();
        assertThat(tasks).hasSize(1);

        tasks.get(0).run();

        assertThat(runs).hasValue(1);
        assertThat(coalescer.completedGeneration()).isEqualTo(3L);
        assertThat(coalescer.requestedGeneration()).isEqualTo(3L);
    }

    private static ConfigServerTracingPolicyAdapter adapter(
            StandardEnvironment environment,
            FakeClient client) {
        return new ConfigServerTracingPolicyAdapter(
                environment,
                manifestBuilder(),
                client,
                client,
                new TracingConfigurationReadiness(),
                new TracingPolicyDeliveryDiagnostics(),
                Runnable::run);
    }

    private static ConfigServerPolicyManifestBuilder manifestBuilder() {
        return new ConfigServerPolicyManifestBuilder(new ConfigServerProvenanceClassifier());
    }

    private static StandardEnvironment environment(boolean complete) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource(
                "configImport",
                Map.of(ConfigServerPolicyManifestBuilder.CONFIG_IMPORT, "configserver:")));
        environment.getPropertySources().addFirst(new MapPropertySource(
                ConfigServerProvenanceClassifier.CONFIG_CLIENT,
                Map.of(
                        "config.client.version", "revision-1",
                        "config.client.state", "state-1")));
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(ConfigServerPolicyManifestBuilder.SAMPLING_ENABLED, true);
        values.put(ConfigServerPolicyManifestBuilder.SAMPLING_RATIO, 0.25d);
        values.put(
                ConfigServerPolicyManifestBuilder.SAMPLING_ROUTE_RATIOS,
                Map.of("/orders", 0.4d));
        values.put(ConfigServerPolicyManifestBuilder.VALIDATION_ENABLED, true);
        values.put(ConfigServerPolicyManifestBuilder.VALIDATION_STRICT, false);
        values.put(ConfigServerPolicyManifestBuilder.EXPORTER_ENABLED, true);
        values.put(ConfigServerPolicyManifestBuilder.TRACE_CONTROL_HEADERS_ENABLED, true);
        if (complete) {
            values.put(ConfigServerPolicyManifestBuilder.DIAGNOSTICS_LOG_LEVEL, "INFO");
        }
        environment.getPropertySources().addAfter(
                ConfigServerProvenanceClassifier.CONFIG_CLIENT,
                new OriginTrackedMapPropertySource("configserver:test", values));
        return environment;
    }

    private static final class FakeClient
            implements AcceptedTracingPolicyReader, TracingPolicySnapshotClient {

        private final AtomicInteger sequence = new AtomicInteger(10);
        private TracingPolicyDeliveryOutcome firstOutcome = TracingPolicyDeliveryOutcome.APPLIED;
        private int applyCount;
        private int readCount;

        @Override
        public AcceptedTracingPolicyView current() {
            readCount++;
            return new AcceptedTracingPolicyView(
                    UUID.randomUUID().toString(),
                    sequence.get(),
                    "a".repeat(64),
                    new CompleteTracingPolicySnapshot(
                            true,
                            0.1d,
                            Map.of(),
                            true,
                            false,
                            true,
                            true,
                            OtelAgentLogLevel.INFO),
                    "APPLIED",
                    UUID.randomUUID().toString(),
                    "NONE",
                    false,
                    false);
        }

        @Override
        public TracingPolicyDeliveryResult applySnapshot(
                ExpectedAcceptedState expectedState,
                CompleteTracingPolicySnapshot snapshot,
                TracingPolicySource source,
                TracingPolicyApplyTrigger trigger,
                String sourceRevision) {
            applyCount++;
            TracingPolicyDeliveryOutcome outcome =
                    applyCount == 1 ? firstOutcome : TracingPolicyDeliveryOutcome.APPLIED;
            if (outcome == TracingPolicyDeliveryOutcome.APPLIED) {
                sequence.incrementAndGet();
            }
            return new TracingPolicyDeliveryResult(
                    outcome,
                    UUID.randomUUID().toString(),
                    "NONE",
                    sequence.get());
        }
    }
}
