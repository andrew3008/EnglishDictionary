package space.br1440.platform.tracing.otel.extension.jmx;

import lombok.extern.slf4j.Slf4j;
import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocolDecoder;
import space.br1440.platform.tracing.core.control.protocol.RuntimePolicyApplier;
import space.br1440.platform.tracing.core.control.protocol.RuntimePolicyControlHandler;
import space.br1440.platform.tracing.otel.extension.control.JmxRuntimePolicyApplier;
import space.br1440.platform.tracing.otel.extension.control.ReadAppliedStateHandler;
import space.br1440.platform.tracing.otel.extension.exporter.SafeSpanExporter;
import space.br1440.platform.tracing.otel.extension.jmx.control.PlatformControlProtocolMBean;
import space.br1440.platform.tracing.otel.extension.jmx.diagnostics.PlatformDiagnosticsControl;
import space.br1440.platform.tracing.otel.extension.jmx.export.PlatformExportControl;
import space.br1440.platform.tracing.otel.extension.jmx.metrics.PlatformProcessorMetricsControl;
import space.br1440.platform.tracing.otel.extension.jmx.sampling.PlatformSamplingControl;
import space.br1440.platform.tracing.otel.extension.jmx.scrubbing.PlatformScrubbingControl;
import space.br1440.platform.tracing.otel.extension.jmx.validation.PlatformValidationControl;
import space.br1440.platform.tracing.otel.extension.processor.MetricsSpanProcessor;
import space.br1440.platform.tracing.otel.extension.processor.PlatformCompositeSpanProcessor;
import space.br1440.platform.tracing.otel.extension.processor.PlatformDropOldestExportSpanProcessor;
import space.br1440.platform.tracing.otel.extension.processor.ScrubbingSpanProcessor;
import space.br1440.platform.tracing.otel.extension.processor.SpanWatchdogProcessor;
import space.br1440.platform.tracing.otel.extension.processor.ValidatingSpanProcessor;
import space.br1440.platform.tracing.otel.extension.sampler.CompositeSampler;
import space.br1440.platform.tracing.otel.extension.sampler.SamplerStateHolder;

import javax.management.InstanceNotFoundException;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * Atomic batch JMX registrar for all platform-tracing MBeans.
 *
 * <p>Registration is deferred until the minimum required holder
 * ({@link SamplerStateHolder}) is set. The batch is all-or-nothing:
 * if any single registration fails the already-registered MBeans are
 * rolled back automatically.
 *
 * <h2>MBeans registered (in order)</h2>
 * <ol>
 *   <li>PlatformSamplingControl</li>
 *   <li>PlatformScrubbingControl</li>
 *   <li>PlatformValidationControl</li>
 *   <li>PlatformExportControl</li>
 *   <li>PlatformProcessorMetricsControl</li>
 *   <li>PlatformDiagnosticsControl</li>
 *   <li>PlatformControlProtocolMBean  (unified control-protocol facade)</li>
 * </ol>
 */
@Slf4j
public final class PlatformTracingJmxRegistrar {

    private final MBeanServer mbeanServer;
    final         LongAdder   sharedInvalidConfigCounter = new LongAdder();

    private final AtomicReference<SamplerStateHolder>                    registeredConfigHolder    = new AtomicReference<>();
    private final AtomicReference<CompositeSampler>                      registeredCompositeSampler = new AtomicReference<>();
    private final AtomicReference<SpanWatchdogProcessor>                 registeredWatchdog        = new AtomicReference<>();
    private final AtomicReference<PlatformCompositeSpanProcessor>        registeredComposite       = new AtomicReference<>();
    private final AtomicReference<MetricsSpanProcessor>                  registeredMetrics         = new AtomicReference<>();
    private final AtomicReference<ScrubbingSpanProcessor>                registeredScrubbing       = new AtomicReference<>();
    private final AtomicReference<ValidatingSpanProcessor>               registeredValidating      = new AtomicReference<>();
    private final AtomicReference<PlatformDropOldestExportSpanProcessor> registeredExportProcessor = new AtomicReference<>();
    private final AtomicReference<SafeSpanExporter>                      registeredSafeExporter    = new AtomicReference<>();
    private final AtomicReference<RuntimePolicyControlHandler>           controlHandler            = new AtomicReference<>();

    private volatile boolean mbeansRegistered;

    public PlatformTracingJmxRegistrar() {
        this(ManagementFactory.getPlatformMBeanServer());
    }

    PlatformTracingJmxRegistrar(MBeanServer mbeanServer) {
        this.mbeanServer = mbeanServer;
    }

    // =========================================================================
    // Holder setters
    // =========================================================================

    public void setConfigHolder(SamplerStateHolder configHolder) {
        registeredConfigHolder.set(configHolder);
        tryRegisterMBeans();
    }

    public void setCompositeSampler(CompositeSampler compositeSampler) {
        registeredCompositeSampler.set(compositeSampler);
        tryRegisterMBeans();
    }

    public void setWatchdog(SpanWatchdogProcessor watchdog) {
        registeredWatchdog.set(watchdog);
        tryRegisterMBeans();
    }

    public void setComposite(PlatformCompositeSpanProcessor composite) {
        registeredComposite.set(composite);
        tryRegisterMBeans();
    }

    public void setMetrics(MetricsSpanProcessor metrics) {
        registeredMetrics.set(metrics);
        tryRegisterMBeans();
    }

    public void setScrubbing(ScrubbingSpanProcessor scrubbing) {
        registeredScrubbing.set(scrubbing);
        tryRegisterMBeans();
    }

    public void setValidating(ValidatingSpanProcessor validating) {
        registeredValidating.set(validating);
        tryRegisterMBeans();
    }

    public void setExportProcessor(PlatformDropOldestExportSpanProcessor exportProcessor) {
        registeredExportProcessor.set(exportProcessor);
        tryRegisterMBeans();
    }

    public void setSafeExporter(SafeSpanExporter safeExporter) {
        registeredSafeExporter.set(safeExporter);
        tryRegisterMBeans();
    }

    /**
     * Sets the unified control-protocol handler and triggers batch
     * registration if the minimum prerequisite (configHolder) is already set.
     *
     * <p>Must be called <em>after</em> both
     * {@link #setConfigHolder(SamplerStateHolder)} and
     * {@link #setValidating(ValidatingSpanProcessor)} to guarantee that the
     * applier inside the handler references live JMX-visible holders.
     */
    public void setControlHandler(RuntimePolicyControlHandler handler) {
        controlHandler.set(handler);
        tryRegisterMBeans();
    }

    // =========================================================================
    // Accessors
    // =========================================================================

    public PlatformDropOldestExportSpanProcessor getExportProcessor() {
        return registeredExportProcessor.get();
    }

    public SafeSpanExporter getSafeExporter() {
        return registeredSafeExporter.get();
    }

    /**
     * Exposes the shared invalid-config counter so
     * {@link space.br1440.platform.tracing.otel.extension.factory.PlatformSpanProcessorFactory}
     * can pass it to the control-protocol applier without duplicating state.
     */
    public LongAdder sharedInvalidConfigCounter() {
        return sharedInvalidConfigCounter;
    }

    /**
     * Builds a fresh {@link PlatformSamplingControl} backed by the
     * currently-held {@link SamplerStateHolder} and shared counter.
     *
     * <p>Package-visible: used by the factory and integration tests.
     */
    PlatformSamplingControl buildSamplingControl() {
        return new PlatformSamplingControl(
                registeredConfigHolder.get(),
                registeredCompositeSampler.get(),
                sharedInvalidConfigCounter);
    }

    // =========================================================================
    // Registration lifecycle
    // =========================================================================

    public void tryRegisterMBeans() {
        if (mbeansRegistered) return;
        if (registeredConfigHolder.get() == null) return;
        synchronized (this) {
            if (mbeansRegistered) return;
            registerAllOrRollback();
            mbeansRegistered = true;
        }
    }

    public void unregisterAllMBeans() {
        synchronized (this) {
            MBeanServer server = mbeanServer;
            unregisterSilently(server, PlatformTracingObjectNames.SAMPLING);
            unregisterSilently(server, PlatformTracingObjectNames.SCRUBBING);
            unregisterSilently(server, PlatformTracingObjectNames.VALIDATION);
            unregisterSilently(server, PlatformTracingObjectNames.EXPORT);
            unregisterSilently(server, PlatformTracingObjectNames.PROCESSOR_METRICS);
            unregisterSilently(server, PlatformTracingObjectNames.DIAGNOSTICS);
            unregisterSilently(server, PlatformTracingObjectNames.CONTROL_PROTOCOL);
            mbeansRegistered = false;
        }
    }

    // =========================================================================
    // Internal
    // =========================================================================

    private void registerAllOrRollback() {
        MBeanServer server = mbeanServer;

        PlatformSamplingControl   samplingControl   = buildSamplingControl();
        PlatformValidationControl validationControl = new PlatformValidationControl(
                registeredValidating.get(), sharedInvalidConfigCounter);

        RuntimePolicyControlHandler handler = controlHandler.get();
        PlatformControlProtocolMBean controlMBean;
        if (handler != null
                && registeredConfigHolder.get() != null
                && registeredValidating.get()   != null) {
            JmxRuntimePolicyApplier applier = new JmxRuntimePolicyApplier(
                    samplingControl, validationControl);
            ReadAppliedStateHandler readHandler = new ReadAppliedStateHandler(
                    registeredConfigHolder.get(), registeredValidating.get());
            controlMBean = new PlatformControlProtocolMBean(
                    TracingControlProtocolDecoder.v1(),
                    handler,
                    readHandler,
                    sharedInvalidConfigCounter);
        } else {
            log.warn("PlatformControlProtocolMBean registered without a control handler "
                    + "— applyPolicy will return DECODE_REJECTED until handler is set.");
            ValidatingSpanProcessor stub =
                    registeredValidating.get() != null
                            ? registeredValidating.get()
                            : new ValidatingSpanProcessor(false, false);
            controlMBean = new PlatformControlProtocolMBean(
                    TracingControlProtocolDecoder.v1(),
                    new RuntimePolicyControlHandler(new NoOpApplier()),
                    new ReadAppliedStateHandler(registeredConfigHolder.get(), stub),
                    sharedInvalidConfigCounter);
        }

        Object[]     mbeans = {
                samplingControl,
                new PlatformScrubbingControl(registeredScrubbing.get(), sharedInvalidConfigCounter),
                validationControl,
                new PlatformExportControl(this::getExportProcessor, this::getSafeExporter),
                new PlatformProcessorMetricsControl(
                        registeredWatchdog.get(),
                        registeredComposite.get(),
                        registeredMetrics.get()),
                new PlatformDiagnosticsControl(sharedInvalidConfigCounter),
                controlMBean
        };
        ObjectName[] names = {
                PlatformTracingObjectNames.SAMPLING,
                PlatformTracingObjectNames.SCRUBBING,
                PlatformTracingObjectNames.VALIDATION,
                PlatformTracingObjectNames.EXPORT,
                PlatformTracingObjectNames.PROCESSOR_METRICS,
                PlatformTracingObjectNames.DIAGNOSTICS,
                PlatformTracingObjectNames.CONTROL_PROTOCOL
        };

        List<ObjectName> registered = new ArrayList<>(names.length);
        Throwable primaryFailure = null;

        for (int i = 0; i < names.length; i++) {
            try {
                replaceExisting(server, mbeans[i], names[i]);
                registered.add(names[i]);
            } catch (Exception e) {
                primaryFailure = e;
                break;
            }
        }

        if (primaryFailure != null) {
            log.error("Domain MBean batch registration did not complete "
                    + "— rolling back {} MBean(s): {}",
                    registered.size(), primaryFailure.getMessage());
            rollback(server, registered, primaryFailure);
        } else {
            log.info("Domain MBeans registered: sampling={}, scrubbing={}, validation={}, "
                    + "export={}, processorMetrics={}, diagnostics={}, controlProtocol={}",
                    PlatformTracingObjectNames.SAMPLING_OBJECT_NAME_STR,
                    PlatformTracingObjectNames.SCRUBBING_OBJECT_NAME_STR,
                    PlatformTracingObjectNames.VALIDATION_OBJECT_NAME_STR,
                    PlatformTracingObjectNames.EXPORT_OBJECT_NAME_STR,
                    PlatformTracingObjectNames.PROCESSOR_METRICS_OBJECT_NAME_STR,
                    PlatformTracingObjectNames.DIAGNOSTICS_OBJECT_NAME_STR,
                    PlatformTracingObjectNames.CONTROL_PROTOCOL_OBJECT_NAME_STR);
        }
    }

    private static void replaceExisting(MBeanServer server, Object mbean, ObjectName name)
            throws Exception {
        if (server.isRegistered(name)) server.unregisterMBean(name);
        server.registerMBean(mbean, name);
    }

    private static void rollback(MBeanServer server,
                                  List<ObjectName> toRollback,
                                  Throwable cause) {
        PlatformTracingJmxRegistrationException ex =
                new PlatformTracingJmxRegistrationException(
                        "Domain MBean batch JMX registration failed: " + cause.getMessage(),
                        cause);
        for (int i = toRollback.size() - 1; i >= 0; i--) {
            try { server.unregisterMBean(toRollback.get(i)); }
            catch (Exception re) { ex.addSuppressed(re); }
        }
        throw ex;
    }

    private static void unregisterSilently(MBeanServer server, ObjectName name) {
        try {
            if (server.isRegistered(name)) server.unregisterMBean(name);
        } catch (InstanceNotFoundException ignored) {
        } catch (Exception e) {
            log.debug("Failed to unregister MBean {}: {}", name, e.getMessage());
        }
    }

    // =========================================================================
    // Inner: no-op applier (fallback when handler not yet wired)
    // =========================================================================

    private static final class NoOpApplier implements RuntimePolicyApplier {
        @Override
        public void apply(
                space.br1440.platform.tracing.api.control.protocol.TracingControlProtocolDecodeResult decoded) {
            // intentionally no-op
        }
    }
}
