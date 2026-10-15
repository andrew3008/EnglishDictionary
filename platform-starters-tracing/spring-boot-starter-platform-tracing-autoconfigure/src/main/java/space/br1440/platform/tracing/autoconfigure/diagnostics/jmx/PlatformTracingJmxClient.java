package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import javax.management.MBeanServer;

import space.br1440.platform.tracing.autoconfigure.agent.AgentReadinessCoherenceGuard;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxDiagnosticSnapshot;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxReadExecutor;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;

public final class PlatformTracingJmxClient {

    private final JmxReadExecutor executor;
    private final SamplingJmxView sampling;
    private final ScrubbingJmxView scrubbing;
    private final ValidationJmxView validation;
    private final ExportJmxView export;
    private final ProcessorMetricsJmxView processorMetrics;
    private final DiagnosticsJmxView diagnostics;

    public PlatformTracingJmxClient(AgentReadinessCoherenceGuard coherenceGuard) {
        this(ManagementFactory.getPlatformMBeanServer(), coherenceGuard);
    }

    PlatformTracingJmxClient(MBeanServer server) {
        this(server, ignored -> {
        });
    }

    public PlatformTracingJmxClient(MBeanServer server,
                                    AgentReadinessCoherenceGuard coherenceGuard) {
        this(
                server,
                Objects.requireNonNull(coherenceGuard, "coherenceGuard")::requirePinnedAgentInstanceId
        );
    }

    private PlatformTracingJmxClient(MBeanServer server,
                                     Consumer<String> agentInstanceIdVerifier) {
        this.executor = new JmxReadExecutor(server, agentInstanceIdVerifier);

        this.sampling = new SamplingJmxView(
                executor,
                JmxWireContract.SAMPLING_OBSERVATION_OBJECT_NAME
        );

        this.scrubbing = new ScrubbingJmxView(
                executor,
                JmxWireContract.SCRUBBING_OBSERVATION_OBJECT_NAME
        );

        this.validation = new ValidationJmxView(
                executor,
                JmxWireContract.VALIDATION_OBSERVATION_OBJECT_NAME
        );

        this.export = new ExportJmxView(
                executor,
                JmxWireContract.EXPORT_OBSERVATION_OBJECT_NAME
        );

        this.processorMetrics = new ProcessorMetricsJmxView(
                executor,
                JmxWireContract.PROCESSOR_METRICS_OBSERVATION_OBJECT_NAME
        );

        this.diagnostics = new DiagnosticsJmxView(
                executor,
                JmxWireContract.DIAGNOSTICS_OBSERVATION_OBJECT_NAME
        );
    }

    public SamplingJmxView sampling() {
        return sampling;
    }

    public ScrubbingJmxView scrubbing() {
        return scrubbing;
    }

    public ValidationJmxView validation() {
        return validation;
    }

    public ExportJmxView export() {
        return export;
    }

    public ProcessorMetricsJmxView processorMetrics() {
        return processorMetrics;
    }

    public DiagnosticsJmxView diagnostics() {
        return diagnostics;
    }

    public JmxMBeanRegistrationSnapshot registrationSnapshot() {
        return new JmxMBeanRegistrationSnapshot(
                sampling.registration(),
                scrubbing.registration(),
                validation.registration(),
                export.registration(),
                processorMetrics.registration(),
                diagnostics.registration()
        );
    }

    public List<JmxDiagnosticSnapshot> jmxContractDiagnostics() {
        return executor.diagnosticSnapshot();
    }
}
