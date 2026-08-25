package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx;

import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxDiagnosticSnapshot;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxReadExecutor;

import javax.management.MBeanServer;
import java.lang.management.ManagementFactory;
import java.util.List;

public final class PlatformTracingJmxClient {

    private final JmxReadExecutor executor;
    private final SamplingJmxView sampling;
    private final ScrubbingJmxView scrubbing;
    private final ValidationJmxView validation;
    private final ExportJmxView export;
    private final ProcessorMetricsJmxView processorMetrics;
    private final DiagnosticsJmxView diagnostics;

    public PlatformTracingJmxClient() {
        this(ManagementFactory.getPlatformMBeanServer());
    }

    public PlatformTracingJmxClient(MBeanServer server) {
        this.executor = new JmxReadExecutor(server);
        this.sampling = new SamplingJmxView(executor, PlatformTracingJmxObjectNames.SAMPLING);
        this.scrubbing = new ScrubbingJmxView(executor, PlatformTracingJmxObjectNames.SCRUBBING);
        this.validation = new ValidationJmxView(executor, PlatformTracingJmxObjectNames.VALIDATION);
        this.export = new ExportJmxView(executor, PlatformTracingJmxObjectNames.EXPORT);
        this.processorMetrics = new ProcessorMetricsJmxView(executor, PlatformTracingJmxObjectNames.PROCESSOR_METRICS);
        this.diagnostics = new DiagnosticsJmxView(executor, PlatformTracingJmxObjectNames.DIAGNOSTICS);
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
                diagnostics.registration());
    }

    public List<JmxDiagnosticSnapshot> jmxContractDiagnostics() {
        return executor.diagnosticSnapshot();
    }
}
