package space.br1440.platform.tracing.otel.extension.jmx;

import lombok.extern.slf4j.Slf4j;
import space.br1440.platform.tracing.otel.extension.exporter.SafeSpanExporter;
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

@Slf4j
public final class PlatformTracingJmxRegistrar {

    private final MBeanServer mbeanServer;
    private final LongAdder sharedInvalidConfigCounter = new LongAdder();

    private final AtomicReference<SamplerStateHolder> registeredConfigHolder = new AtomicReference<>();
    private final AtomicReference<CompositeSampler> registeredCompositeSampler = new AtomicReference<>();
    private final AtomicReference<SpanWatchdogProcessor> registeredWatchdog = new AtomicReference<>();
    private final AtomicReference<PlatformCompositeSpanProcessor> registeredComposite = new AtomicReference<>();
    private final AtomicReference<MetricsSpanProcessor> registeredMetrics = new AtomicReference<>();
    private final AtomicReference<ScrubbingSpanProcessor> registeredScrubbing = new AtomicReference<>();
    private final AtomicReference<ValidatingSpanProcessor> registeredValidating = new AtomicReference<>();
    private final AtomicReference<PlatformDropOldestExportSpanProcessor> registeredExportProcessor = new AtomicReference<>();
    private final AtomicReference<SafeSpanExporter> registeredSafeExporter = new AtomicReference<>();

    private volatile boolean mbeansRegistered;

    public PlatformTracingJmxRegistrar() {
        this(ManagementFactory.getPlatformMBeanServer());
    }

    PlatformTracingJmxRegistrar(MBeanServer mbeanServer) {
        this.mbeanServer = mbeanServer;
    }

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

    public PlatformDropOldestExportSpanProcessor getExportProcessor() {
        return registeredExportProcessor.get();
    }

    public SafeSpanExporter getSafeExporter() {
        return registeredSafeExporter.get();
    }

    public void tryRegisterMBeans() {
        if (mbeansRegistered) {
            return;
        }

        if (registeredConfigHolder.get() == null) {
            return;
        }

        synchronized (this) {
            if (mbeansRegistered) {
                return;
            }

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
            mbeansRegistered = false;
        }
    }

    private void registerAllOrRollback() {
        MBeanServer server = mbeanServer;

        Object[] mbeans = {
                new PlatformSamplingControl(
                        registeredConfigHolder.get(),
                        registeredCompositeSampler.get(),
                        sharedInvalidConfigCounter
                ),

                new PlatformScrubbingControl(
                        registeredScrubbing.get(),
                        sharedInvalidConfigCounter
                ),

                new PlatformValidationControl(
                        registeredValidating.get(),
                        sharedInvalidConfigCounter
                ),

                new PlatformExportControl(
                        this::getExportProcessor,
                        this::getSafeExporter
                ),

                new PlatformProcessorMetricsControl(
                        registeredWatchdog.get(),
                        registeredComposite.get(),
                        registeredMetrics.get()
                ),

                new PlatformDiagnosticsControl(sharedInvalidConfigCounter)
        };

        ObjectName[] names = {
                PlatformTracingObjectNames.SAMPLING,
                PlatformTracingObjectNames.SCRUBBING,
                PlatformTracingObjectNames.VALIDATION,
                PlatformTracingObjectNames.EXPORT,
                PlatformTracingObjectNames.PROCESSOR_METRICS,
                PlatformTracingObjectNames.DIAGNOSTICS
        };

        List<ObjectName> registered = new ArrayList<>(names.length);
        Throwable primaryFailure = null;

        for (int i = 0; i < names.length; i++) {
            ObjectName name = names[i];
            Object mbean = mbeans[i];

            try {
                replaceExisting(server, mbean, name);
                registered.add(name);
            } catch (Exception e) {
                primaryFailure = e;
                break;
            }
        }

        if (primaryFailure != null) {
            log.error("""
                    Domain MBean batch registration did not complete — rolling back {} MBean(s): {}\
                    """,
                    registered.size(), primaryFailure.getMessage());
            rollback(server, registered, primaryFailure);
        } else {
            log.info("""
                    Domain MBeans registered: sampling={}, scrubbing={}, validation={}, \
                    export={}, processorMetrics={}, diagnostics={}\
                    """,
                    PlatformTracingObjectNames.SAMPLING_OBJECT_NAME_STR,
                    PlatformTracingObjectNames.SCRUBBING_OBJECT_NAME_STR,
                    PlatformTracingObjectNames.VALIDATION_OBJECT_NAME_STR,
                    PlatformTracingObjectNames.EXPORT_OBJECT_NAME_STR,
                    PlatformTracingObjectNames.PROCESSOR_METRICS_OBJECT_NAME_STR,
                    PlatformTracingObjectNames.DIAGNOSTICS_OBJECT_NAME_STR);
        }
    }

    private static void replaceExisting(MBeanServer server, Object mbean, ObjectName name) throws Exception {
        if (server.isRegistered(name)) {
            server.unregisterMBean(name);
        }

        server.registerMBean(mbean, name);
    }

    private static void rollback(MBeanServer server, List<ObjectName> toRollback, Throwable cause) {
        String message = "Domain MBean batch JMX registration failed: " + cause.getMessage();
        PlatformTracingJmxRegistrationException ex = new PlatformTracingJmxRegistrationException(message, cause);
        for (int i = toRollback.size() - 1; i >= 0; i--) {
            try {
                server.unregisterMBean(toRollback.get(i));
            } catch (Exception rollbackEx) {
                ex.addSuppressed(rollbackEx);
            }
        }

        throw ex;
    }

    private static void unregisterSilently(MBeanServer server, ObjectName name) {
        try {
            if (server.isRegistered(name)) {
                server.unregisterMBean(name);
            }
        } catch (InstanceNotFoundException ignored) {
        } catch (Exception e) {
            log.debug("Failed to unregister MBean {}: {}", name, e.getMessage());
        }
    }
}
