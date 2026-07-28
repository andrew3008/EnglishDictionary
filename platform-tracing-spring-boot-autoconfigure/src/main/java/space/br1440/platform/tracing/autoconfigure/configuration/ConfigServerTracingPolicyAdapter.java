package space.br1440.platform.tracing.autoconfigure.configuration;

import java.util.Objects;
import java.util.concurrent.Executor;

import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Config Server adapter полного Category A snapshot.
 */
final class ConfigServerTracingPolicyAdapter
        implements ApplicationListener<EnvironmentChangeEvent> {

    private final ConfigurableEnvironment environment;
    private final ConfigServerPolicyManifestBuilder manifestBuilder;
    private final AcceptedTracingPolicyReader acceptedReader;
    private final TracingPolicySnapshotClient snapshotClient;
    private final TracingConfigurationReadiness readiness;
    private final TracingPolicyDeliveryDiagnostics diagnostics;
    private final TracingPolicyRefreshCoalescer coalescer;

    ConfigServerTracingPolicyAdapter(
            ConfigurableEnvironment environment,
            ConfigServerPolicyManifestBuilder manifestBuilder,
            AcceptedTracingPolicyReader acceptedReader,
            TracingPolicySnapshotClient snapshotClient,
            TracingConfigurationReadiness readiness,
            TracingPolicyDeliveryDiagnostics diagnostics,
            Executor refreshExecutor) {
        this.environment = Objects.requireNonNull(environment, "environment");
        this.manifestBuilder = Objects.requireNonNull(manifestBuilder, "manifestBuilder");
        this.acceptedReader = Objects.requireNonNull(acceptedReader, "acceptedReader");
        this.snapshotClient = Objects.requireNonNull(snapshotClient, "snapshotClient");
        this.readiness = Objects.requireNonNull(readiness, "readiness");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.coalescer = new TracingPolicyRefreshCoalescer(
                Objects.requireNonNull(refreshExecutor, "refreshExecutor"),
                generation -> refresh(),
                (generation, exception) -> recordFailure("REFRESH_EVALUATION_FAILED"));
    }

    TracingPolicyDeliveryResult applyInitial() {
        TracingPolicyDeliveryResult result = deliver(TracingPolicyApplyTrigger.INITIAL);
        readiness.recordInitial(result);
        return result;
    }

    TracingPolicyDeliveryResult refresh() {
        TracingPolicyDeliveryResult first = deliver(TracingPolicyApplyTrigger.REFRESH);
        if (first.outcome() != TracingPolicyDeliveryOutcome.STALE) {
            return first;
        }
        // После STALE заново читаются authoritative manifest и expected accepted base.
        return deliver(TracingPolicyApplyTrigger.REFRESH);
    }

    @Override
    public void onApplicationEvent(EnvironmentChangeEvent event) {
        Objects.requireNonNull(event, "event");
        coalescer.signalRefresh();
    }

    TracingPolicyRefreshCoalescer coalescer() {
        return coalescer;
    }

    private TracingPolicyDeliveryResult deliver(TracingPolicyApplyTrigger trigger) {
        ConfigServerPolicyManifestBuilder.CompleteManifest manifest;
        try {
            manifest = manifestBuilder.build(environment);
        } catch (TracingPolicyManifestException exception) {
            return recordRejected("MISSING_OR_NON_AUTHORITATIVE_FIELD");
        }

        AcceptedTracingPolicyView accepted;
        try {
            accepted = acceptedReader.current();
        } catch (RuntimeException exception) {
            return recordFailure("ACCEPTED_STATE_UNAVAILABLE");
        }

        TracingPolicyDeliveryResult result = snapshotClient.applySnapshot(
                ExpectedAcceptedState.from(accepted),
                manifest.policy(),
                TracingPolicySource.CONFIG_SERVER,
                trigger,
                manifest.sourceRevision());
        diagnostics.record(
                TracingPolicySource.CONFIG_SERVER,
                manifest.sourceRevision(),
                result);
        return result;
    }

    private TracingPolicyDeliveryResult recordRejected(String messageCode) {
        long sequence = currentSequence();
        TracingPolicyDeliveryResult result = new TracingPolicyDeliveryResult(
                TracingPolicyDeliveryOutcome.REJECTED,
                "",
                messageCode,
                sequence);
        diagnostics.record(TracingPolicySource.CONFIG_SERVER, "", result);
        return result;
    }

    private TracingPolicyDeliveryResult recordFailure(String messageCode) {
        long sequence = currentSequence();
        TracingPolicyDeliveryResult result = new TracingPolicyDeliveryResult(
                TracingPolicyDeliveryOutcome.FAILED,
                "",
                messageCode,
                sequence);
        diagnostics.record(TracingPolicySource.CONFIG_SERVER, "", result);
        return result;
    }

    private long currentSequence() {
        try {
            return acceptedReader.current().acceptedSequence();
        } catch (RuntimeException exception) {
            return 0L;
        }
    }
}
