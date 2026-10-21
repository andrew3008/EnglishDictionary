package space.br1440.platform.tracing.otel.javaagent.jmx.registration;

import java.lang.management.ManagementFactory;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import javax.management.MBeanServer;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.exporter.SafeSpanExporter;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionReadinessWireSnapshot;
import space.br1440.platform.tracing.otel.javaagent.processor.SpanLimitsMetricsProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.ValidatingSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.composite.PlatformCompositeSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.dropoldest.PlatformDropOldestExportSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.spantimeout.SpanTimeoutProcessor;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionReadiness;
import space.br1440.platform.tracing.otel.javaagent.sampler.CompositeSampler;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntime;

public final class PlatformTracingMBeanRegistrar {

    public interface RuntimeRegistration extends AutoCloseable {

        UUID batchId();

        @Override
        void close();
    }

    private final PlatformExtensionReadiness extensionReadiness;
    private final DiagnosticReadinessRegistrar diagnosticPlane;
    private final RuntimeControlsRegistrar runtimePlane;
    private final AgentInstanceId agentInstanceId;

    public PlatformTracingMBeanRegistrar(AgentInstanceId agentInstanceId) {
        this(ManagementFactory.getPlatformMBeanServer(), agentInstanceId);
    }

    public PlatformTracingMBeanRegistrar(MBeanServer mbeanServer, AgentInstanceId agentInstanceId) {
        this(mbeanServer, PlatformExtensionReadiness.forCurrentExtension(), agentInstanceId);
    }

    PlatformTracingMBeanRegistrar(MBeanServer mbeanServer,
                                  PlatformExtensionReadiness extensionReadiness,
                                  AgentInstanceId agentInstanceId) {
        AgentInstanceId requiredAgentInstanceId = Objects.requireNonNull(agentInstanceId, "agentInstanceId");

        MBeanRegistrationScope scope = new MBeanRegistrationScope(mbeanServer);
        this.extensionReadiness = Objects.requireNonNull(extensionReadiness, "extensionReadiness");
        this.diagnosticPlane = new DiagnosticReadinessRegistrar(scope);
        this.agentInstanceId = requiredAgentInstanceId;
        this.runtimePlane = new RuntimeControlsRegistrar(scope, requiredAgentInstanceId);
    }

    public AutoCloseable registerDiagnosticReadiness(Supplier<ExtensionReadinessWireSnapshot> projection) {
        Objects.requireNonNull(projection, "projection");
        return diagnosticPlane.register(() -> requireOwnedReadinessSnapshot(projection.get()));
    }

    public RuntimeRegistration registerRuntimeControls(TracingSettingsSource acceptedPolicy) {
        Objects.requireNonNull(acceptedPolicy, "acceptedPolicy");
        if (!agentInstanceId.equals(acceptedPolicy.current().agentInstanceId())) {
            throw new IllegalArgumentException(
                    "JMX registrar and tracing settings agentInstanceId values must match"
            );
        }

        return runtimePlane.register(acceptedPolicy);
    }

    public void setCompositeSampler(CompositeSampler compositeSampler) {
        runtimePlane.setCompositeSampler(compositeSampler);
    }

    public void setSpanTimeout(SpanTimeoutProcessor spanTimeout) {
        runtimePlane.setSpanTimeout(spanTimeout);
    }

    public void setComposite(PlatformCompositeSpanProcessor composite) {
        runtimePlane.setComposite(composite);
    }

    public void setMetrics(SpanLimitsMetricsProcessor metrics) {
        runtimePlane.setMetrics(metrics);
    }

    public void setScrubbing(ScrubbingRuntime scrubbing) {
        runtimePlane.setScrubbing(scrubbing);
    }

    public void setValidating(ValidatingSpanProcessor validating) {
        runtimePlane.setValidating(validating);
    }

    public void setExportProcessor(PlatformDropOldestExportSpanProcessor exportProcessor) {
        runtimePlane.setExportProcessor(exportProcessor);
    }

    public void setSafeExporter(SafeSpanExporter safeExporter) {
        runtimePlane.setSafeExporter(safeExporter);
    }

    public PlatformExtensionReadiness extensionReadiness() {
        return extensionReadiness;
    }

    public AgentInstanceId agentInstanceId() {
        return agentInstanceId;
    }

    private ExtensionReadinessWireSnapshot requireOwnedReadinessSnapshot(ExtensionReadinessWireSnapshot snapshot) {
        ExtensionReadinessWireSnapshot requiredSnapshot = Objects.requireNonNull(
                snapshot,
                "Readiness projection must not return null"
        );

        if (!agentInstanceId.toString().equals(requiredSnapshot.agentInstanceId())) {
            throw new IllegalStateException(
                    "JMX registrar and readiness snapshot agentInstanceId values must match"
            );
        }

        return requiredSnapshot;
    }
}
