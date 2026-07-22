package space.br1440.platform.tracing.otel.javaagent.jmx;

import lombok.extern.slf4j.Slf4j;
import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocol;
import space.br1440.platform.tracing.otel.control.protocol.RuntimePolicyApplier;
import space.br1440.platform.tracing.otel.control.protocol.RuntimePolicyControlHandler;
import space.br1440.platform.tracing.otel.javaagent.control.ReadAppliedStateHandler;
import space.br1440.platform.tracing.otel.javaagent.exporter.SafeSpanExporter;
import space.br1440.platform.tracing.otel.javaagent.jmx.control.PlatformControlProtocolMBean;
import space.br1440.platform.tracing.otel.javaagent.jmx.control.PlatformControlProtocolMXBean;
import space.br1440.platform.tracing.otel.javaagent.jmx.diagnostics.PlatformDiagnosticsControl;
import space.br1440.platform.tracing.otel.javaagent.jmx.export.PlatformExportControl;
import space.br1440.platform.tracing.otel.javaagent.jmx.metrics.PlatformProcessorMetricsControl;
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
import space.br1440.platform.tracing.otel.javaagent.sampler.SamplerStateHolder;

import javax.management.InstanceNotFoundException;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.StandardMBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final PlatformExtensionReadiness extensionReadiness;
    private final AtomicBoolean shutdownHookInstalled = new AtomicBoolean();
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
        this(ManagementFactory.getPlatformMBeanServer(), new PlatformExtensionReadiness());
    }

    public PlatformTracingJmxRegistrar(MBeanServer mbeanServer) {
        this(mbeanServer, new PlatformExtensionReadiness());
    }

    PlatformTracingJmxRegistrar(MBeanServer mbeanServer, PlatformExtensionReadiness extensionReadiness) {
        this.mbeanServer = mbeanServer;
        this.extensionReadiness = extensionReadiness;
        registerReadinessMBean();
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
        if (!refreshControlProtocolMBeanIfReady()) {
            tryRegisterMBeans();
        }
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
        if (!refreshControlProtocolMBeanIfReady()) {
            tryRegisterMBeans();
        }
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
     * {@link space.br1440.platform.tracing.otel.javaagent.factory.PlatformSpanProcessorFactory}
     * can pass it to the control-protocol applier without duplicating state.
     */
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

    /**
     * Builds a fresh {@link PlatformSamplingControl} backed by the
     * currently-held {@link SamplerStateHolder} and shared counter.
     *
     * <p>Used by the factory and integration tests.
     */
    public PlatformSamplingControl buildSamplingControl() {
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
            unregisterSilently(server, PlatformTracingObjectNames.EXTENSION_READINESS);
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

        PlatformControlProtocolMBean controlMBean = buildControlProtocolMBean(
                samplingControl, validationControl);

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
                standardControlMBean(controlMBean)
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

    private static StandardMBean standardControlMBean(PlatformControlProtocolMBean mbean) {
        return new StandardMBean(mbean, PlatformControlProtocolMXBean.class, false);
    }

    private void registerReadinessMBean() {
        try {
            replaceExisting(
                    mbeanServer,
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

    private boolean refreshControlProtocolMBeanIfReady() {
        synchronized (this) {
            if (!mbeansRegistered
                    || registeredConfigHolder.get() == null
                    || registeredValidating.get() == null
                    || controlHandler.get() == null) {
                return false;
            }

            PlatformSamplingControl samplingControl = buildSamplingControl();
            PlatformValidationControl validationControl = new PlatformValidationControl(
                    registeredValidating.get(), sharedInvalidConfigCounter);
            try {
                replaceExisting(
                        mbeanServer,
                        standardControlMBean(buildControlProtocolMBean(samplingControl, validationControl)),
                        PlatformTracingObjectNames.CONTROL_PROTOCOL);
                log.info("PlatformControlProtocolMBean refreshed with live control handler: {}",
                        PlatformTracingObjectNames.CONTROL_PROTOCOL_OBJECT_NAME_STR);
                return true;
            } catch (Exception e) {
                throw new PlatformTracingJmxRegistrationException(
                        "Control-protocol MBean refresh failed: " + e.getMessage(), e);
            }
        }
    }

    private PlatformControlProtocolMBean buildControlProtocolMBean(
            PlatformSamplingControl samplingControl,
            PlatformValidationControl validationControl) {
        RuntimePolicyControlHandler handler = controlHandler.get();
        if (handler != null
                && registeredConfigHolder.get() != null
                && registeredValidating.get() != null) {
            ReadAppliedStateHandler readHandler = new ReadAppliedStateHandler(
                    registeredConfigHolder.get(), registeredValidating.get());
            return new PlatformControlProtocolMBean(
                    TracingControlProtocol.current(),
                    handler,
                    readHandler,
                    sharedInvalidConfigCounter);
        }

        log.warn("PlatformControlProtocolMBean registered without a control handler: "
                + "applyPolicy will return DECODE_REJECTED until handler is set.");
        ValidatingSpanProcessor stub =
                registeredValidating.get() != null
                        ? registeredValidating.get()
                        : new ValidatingSpanProcessor(false, false);
        return new PlatformControlProtocolMBean(
                TracingControlProtocol.current(),
                new RuntimePolicyControlHandler(new NoOpApplier()),
                new ReadAppliedStateHandler(registeredConfigHolder.get(), stub),
                sharedInvalidConfigCounter);
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
                space.br1440.platform.tracing.api.control.protocol.TracingControlProtocolOperation operation,
                java.util.Map<String, Object> normalizedPayload,
                String source) {
            // intentionally no-op
        }
    }
}
