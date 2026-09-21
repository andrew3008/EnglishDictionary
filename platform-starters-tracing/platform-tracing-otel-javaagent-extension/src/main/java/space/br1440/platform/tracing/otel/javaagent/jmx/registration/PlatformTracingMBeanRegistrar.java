package space.br1440.platform.tracing.otel.javaagent.jmx.registration;

import java.lang.management.ManagementFactory;
import java.util.UUID;
import java.util.function.Supplier;

import javax.management.MBeanServer;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.exporter.SafeSpanExporter;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionReadinessWireSnapshot;
import space.br1440.platform.tracing.otel.javaagent.processor.SpanLimitsMetricsProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.ValidatingSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.composite.PlatformCompositeSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.dropoldest.PlatformDropOldestExportSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.spantimeout.SpanTimeoutProcessor;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionReadiness;
import space.br1440.platform.tracing.otel.javaagent.sampler.CompositeSampler;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.ScrubbingRuntime;

public final class PlatformTracingMBeanRegistrar {

    public interface RuntimeRegistration extends AutoCloseable {

        UUID batchId();

        @Override
        void close();
    }

    private final PlatformExtensionReadiness extensionReadiness;
    private final DiagnosticReadinessRegistrar diagnosticPlane;
    private final RuntimeControlsRegistrar runtimePlane;

    public PlatformTracingMBeanRegistrar() {
        this(ManagementFactory.getPlatformMBeanServer(), new PlatformExtensionReadiness());
    }

    public PlatformTracingMBeanRegistrar(MBeanServer mbeanServer) {
        this(mbeanServer, new PlatformExtensionReadiness());
    }

    PlatformTracingMBeanRegistrar(MBeanServer mbeanServer, PlatformExtensionReadiness extensionReadiness) {
        MBeanRegistrationScope scope = new MBeanRegistrationScope(mbeanServer, extensionReadiness);
        this.extensionReadiness = scope.extensionReadiness();
        this.diagnosticPlane = new DiagnosticReadinessRegistrar(scope);
        this.runtimePlane = new RuntimeControlsRegistrar(scope, UUID.randomUUID());
    }

    public AutoCloseable registerDiagnosticReadiness(Supplier<ExtensionReadinessWireSnapshot> projection) {
        return diagnosticPlane.register(projection);
    }

    public RuntimeRegistration registerRuntimeControls(TracingSettingsSource acceptedPolicy) {
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
}
