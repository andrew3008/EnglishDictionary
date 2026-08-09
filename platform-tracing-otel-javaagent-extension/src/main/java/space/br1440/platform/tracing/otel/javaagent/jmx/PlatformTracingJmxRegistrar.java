package space.br1440.platform.tracing.otel.javaagent.jmx;

import lombok.extern.slf4j.Slf4j;

import space.br1440.platform.tracing.otel.javaagent.control.state.AcceptedPolicyAccess;
import space.br1440.platform.tracing.otel.javaagent.exporter.SafeSpanExporter;
import space.br1440.platform.tracing.otel.javaagent.jmx.diagnostics.PlatformDiagnosticsControl;
import space.br1440.platform.tracing.otel.javaagent.jmx.export.PlatformExportControl;
import space.br1440.platform.tracing.otel.javaagent.jmx.metrics.PlatformProcessorMetricsControl;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerRegistrationSlot;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.PlatformExtensionReadinessControl;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.PlatformExtensionReadinessMBean;
import space.br1440.platform.tracing.otel.javaagent.jmx.sampling.PlatformSamplingControl;
import space.br1440.platform.tracing.otel.javaagent.jmx.scrubbing.PlatformScrubbingControl;
import space.br1440.platform.tracing.otel.javaagent.jmx.validation.PlatformValidationControl;
import space.br1440.platform.tracing.otel.javaagent.processor.MetricsSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.PlatformCompositeSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.PlatformDropOldestExportSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.ScrubbingSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.SpanWatchdogProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.ValidatingSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionReadiness;
import space.br1440.platform.tracing.otel.javaagent.sampler.CompositeSampler;

import javax.management.MBeanServer;
import javax.management.StandardMBean;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

@Slf4j
public final class PlatformTracingJmxRegistrar {

    private final MBeanServer mbeanServer;
    private final PlatformExtensionReadiness extensionReadiness;
    private final AtomicBoolean shutdownHookInstalled = new AtomicBoolean();
    final LongAdder sharedInvalidConfigCounter = new LongAdder();

    private final UUID agentInstanceId = UUID.randomUUID();
    private final AtomicReference<AcceptedPolicyAccess> registeredAcceptedPolicy = new AtomicReference<>();
    private final OwnerRegistrationSlots slots = new OwnerRegistrationSlots();
    private final MBeanBatchRegistrar batchRegistrar;

    private volatile boolean mbeansRegistered;

    public PlatformTracingJmxRegistrar() {
        this(ManagementFactory.getPlatformMBeanServer(), new PlatformExtensionReadiness());
    }

    public PlatformTracingJmxRegistrar(MBeanServer mbeanServer) {
        this(mbeanServer, new PlatformExtensionReadiness());
    }

    PlatformTracingJmxRegistrar(MBeanServer mbeanServer, PlatformExtensionReadiness extensionReadiness) {
        this.mbeanServer = mbeanServer;
        this.extensionReadiness = extensionReadiness;
        this.batchRegistrar = new MBeanBatchRegistrar(mbeanServer);
        registerReadinessMBean();
    }

    // =========================================================================
    // Owner attachment
    // =========================================================================

    public void setAcceptedPolicy(AcceptedPolicyAccess acceptedPolicy) {
        registeredAcceptedPolicy.set(acceptedPolicy);
        tryRegisterMBeans();
    }

    public void setCompositeSampler(CompositeSampler compositeSampler) {
        attachOwner(slots.compositeSampler(), compositeSampler);
    }

    public void setWatchdog(SpanWatchdogProcessor watchdog) {
        attachOwner(slots.watchdog(), watchdog);
    }

    public void setComposite(PlatformCompositeSpanProcessor composite) {
        attachOwner(slots.composite(), composite);
    }

    public void setMetrics(MetricsSpanProcessor metrics) {
        attachOwner(slots.metrics(), metrics);
    }

    public void setScrubbing(ScrubbingSpanProcessor scrubbing) {
        attachOwner(slots.scrubbing(), scrubbing);
    }

    public void setValidating(ValidatingSpanProcessor validating) {
        attachOwner(slots.validating(), validating);
    }

    public void setExportProcessor(PlatformDropOldestExportSpanProcessor exportProcessor) {
        attachOwner(slots.exportProcessor(), exportProcessor);
    }

    public void setSafeExporter(SafeSpanExporter safeExporter) {
        attachOwner(slots.safeExporter(), safeExporter);
    }

    private <T> void attachOwner(OwnerRegistrationSlot<T> slot, T owner) {
        slot.replace(owner);
        tryRegisterMBeans();
    }

    // =========================================================================
    // Accessors
    // =========================================================================

    public OwnerRegistrationSlot<PlatformDropOldestExportSpanProcessor> exportProcessorSlot() {
        return slots.exportProcessor();
    }

    public OwnerRegistrationSlot<SafeSpanExporter> safeExporterSlot() {
        return slots.safeExporter();
    }

    public LongAdder sharedInvalidConfigCounter() {
        return sharedInvalidConfigCounter;
    }

    public PlatformExtensionReadiness extensionReadiness() {
        return extensionReadiness;
    }

    public void installShutdownCleanup() {
        if (!shutdownHookInstalled.compareAndSet(false, true)) {
            return;
        }
        Thread cleanup = new Thread(() -> {
            unregisterAllMBeans();
            log.info("Platform tracing JMX resources unregistered on JVM shutdown");
        }, "platform-tracing-jmx-shutdown");
        Runtime.getRuntime().addShutdownHook(cleanup);
    }

    public PlatformSamplingControl buildSamplingControl() {
        return new PlatformSamplingControl(
                registeredAcceptedPolicy.get(),
                slots.compositeSampler(),
                agentInstanceId);
    }

    // =========================================================================
    // Registration lifecycle
    // =========================================================================

    public void tryRegisterMBeans() {
        if (mbeansRegistered) return;
        if (registeredAcceptedPolicy.get() == null) return;
        synchronized (this) {
            if (mbeansRegistered) return;
            registerAllOrRollback();
            mbeansRegistered = true;
        }
    }

    public void unregisterAllMBeans() {
        synchronized (this) {
            batchRegistrar.unregisterSilently(PlatformTracingObjectNames.SAMPLING);
            batchRegistrar.unregisterSilently(PlatformTracingObjectNames.SCRUBBING);
            batchRegistrar.unregisterSilently(PlatformTracingObjectNames.VALIDATION);
            batchRegistrar.unregisterSilently(PlatformTracingObjectNames.EXPORT);
            batchRegistrar.unregisterSilently(PlatformTracingObjectNames.PROCESSOR_METRICS);
            batchRegistrar.unregisterSilently(PlatformTracingObjectNames.DIAGNOSTICS);
            batchRegistrar.unregisterSilently(PlatformTracingObjectNames.EXTENSION_READINESS);
            mbeansRegistered = false;
        }
    }

    // =========================================================================
    // Internal — tracing-specific composition
    // =========================================================================

    private List<MBeanBatchRegistrar.Registration> domainMBeans() {
        return List.of(
                new MBeanBatchRegistrar.Registration(PlatformTracingObjectNames.SAMPLING, buildSamplingControl()),
                new MBeanBatchRegistrar.Registration(PlatformTracingObjectNames.SCRUBBING,
                        new PlatformScrubbingControl(slots.scrubbing(), agentInstanceId)),
                new MBeanBatchRegistrar.Registration(PlatformTracingObjectNames.VALIDATION,
                        new PlatformValidationControl(slots.validating(), agentInstanceId)),
                new MBeanBatchRegistrar.Registration(PlatformTracingObjectNames.EXPORT,
                        new PlatformExportControl(slots.exportProcessor(), slots.safeExporter(), agentInstanceId)),
                new MBeanBatchRegistrar.Registration(PlatformTracingObjectNames.PROCESSOR_METRICS,
                        new PlatformProcessorMetricsControl(
                                slots.watchdog(), slots.composite(), slots.metrics(), agentInstanceId)),
                new MBeanBatchRegistrar.Registration(PlatformTracingObjectNames.DIAGNOSTICS,
                        new PlatformDiagnosticsControl(sharedInvalidConfigCounter, agentInstanceId))
        );
    }

    private void registerAllOrRollback() {
        batchRegistrar.registerAllOrRollback(domainMBeans());
        log.info("Domain MBeans registered (read-only): sampling={}, scrubbing={}, validation={}, "
                + "export={}, processorMetrics={}, diagnostics={}",
                PlatformTracingObjectNames.SAMPLING_OBJECT_NAME_STR,
                PlatformTracingObjectNames.SCRUBBING_OBJECT_NAME_STR,
                PlatformTracingObjectNames.VALIDATION_OBJECT_NAME_STR,
                PlatformTracingObjectNames.EXPORT_OBJECT_NAME_STR,
                PlatformTracingObjectNames.PROCESSOR_METRICS_OBJECT_NAME_STR,
                PlatformTracingObjectNames.DIAGNOSTICS_OBJECT_NAME_STR);
    }

    private void registerReadinessMBean() {
        try {
            batchRegistrar.replaceExisting(
                    new StandardMBean(
                            new PlatformExtensionReadinessControl(extensionReadiness),
                            PlatformExtensionReadinessMBean.class,
                            false),
                    PlatformTracingObjectNames.EXTENSION_READINESS);
        } catch (Exception e) {
            extensionReadiness.fail("READINESS_MBEAN_REGISTRATION_FAILED", e);
            throw new PlatformTracingJmxRegistrationException(
                    "Extension readiness MBean registration failed: " + e.getMessage(), e);
        }
    }
}
