package space.br1440.platform.tracing.otel.javaagent.jmx.registration;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.exporter.SafeSpanExporter;
import space.br1440.platform.tracing.otel.javaagent.jmx.DomainObjectNames;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerRegistrationSlot;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionReadinessDynamicMBean;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionReadinessWireSnapshot;
import space.br1440.platform.tracing.otel.javaagent.processor.SpanLimitsMetricsProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.ValidatingSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.composite.PlatformCompositeSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.dropoldest.PlatformDropOldestExportSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.watchdog.SpanWatchdogProcessor;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionReadiness;
import space.br1440.platform.tracing.otel.javaagent.sampler.CompositeSampler;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.ScrubbingRuntime;

import javax.management.MBeanServer;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

public final class DomainMBeanRegistrar {

    final LongAdder sharedInvalidConfigCounter = new LongAdder();

    private final PlatformExtensionReadiness extensionReadiness;
    private final UUID agentInstanceId = UUID.randomUUID();
    private final AtomicReference<TracingSettingsSource> registeredAcceptedPolicy = new AtomicReference<>();
    private final OwnerRegistrationSlots slots = new OwnerRegistrationSlots();
    private final MBeanBatchRegistrar batchRegistrar;
    private final AtomicLong diagnosticGeneration = new AtomicLong();
    private final AtomicLong runtimeGeneration = new AtomicLong();
    private final Object registrationLock = new Object();

    private volatile boolean mbeansRegistered;
    private long activeDiagnosticGeneration;
    private long activeRuntimeGeneration;

    public DomainMBeanRegistrar() {
        this(ManagementFactory.getPlatformMBeanServer(), new PlatformExtensionReadiness());
    }

    public DomainMBeanRegistrar(MBeanServer mbeanServer) {
        this(mbeanServer, new PlatformExtensionReadiness());
    }

    DomainMBeanRegistrar(MBeanServer mbeanServer, PlatformExtensionReadiness extensionReadiness) {
        this.extensionReadiness = extensionReadiness;
        this.batchRegistrar = new MBeanBatchRegistrar(mbeanServer);
    }

    public AutoCloseable registerDiagnosticReadiness(Supplier<ExtensionReadinessWireSnapshot> projection) {
        Objects.requireNonNull(projection, "projection");

        synchronized (registrationLock) {
            if (activeDiagnosticGeneration != 0L) {
                throw new IllegalStateException("DomainMBeanRegistrar already has an active diagnostic readiness registration");
            }

            long token = diagnosticGeneration.incrementAndGet();
            registerReadinessMBean(projection);
            activeDiagnosticGeneration = token;
            return new DiagnosticReadinessRegistration(token);
        }
    }

    public AutoCloseable registerDiagnosticReadiness() {
        AtomicReference<ExtensionReadinessWireSnapshot> projection = new AtomicReference<>(
                ExtensionReadinessWireSnapshot.initializing(extensionReadiness)
        );

        return registerDiagnosticReadiness(projection::get);
    }

    public AutoCloseable registerRuntimeControls(TracingSettingsSource acceptedPolicy) {
        Objects.requireNonNull(acceptedPolicy, "acceptedPolicy");

        synchronized (registrationLock) {
            if (activeRuntimeGeneration != 0L) {
                throw new IllegalStateException("DomainMBeanRegistrar already has an active runtime-controls registration");
            }

            TracingSettingsSource previous = registeredAcceptedPolicy.get();
            long token = runtimeGeneration.incrementAndGet();
            registeredAcceptedPolicy.set(acceptedPolicy);
            try {
                tryRegisterMBeans();
            } catch (RuntimeException | Error failure) {
                registeredAcceptedPolicy.set(previous);
                throw failure;
            }

            activeRuntimeGeneration = token;
            return new RuntimeControlsRegistration(token, previous);
        }
    }

    public void setAcceptedPolicy(TracingSettingsSource acceptedPolicy) {
        registeredAcceptedPolicy.set(Objects.requireNonNull(acceptedPolicy, "acceptedPolicy"));
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

    public void setMetrics(SpanLimitsMetricsProcessor metrics) {
        attachOwner(slots.metrics(), metrics);
    }

    public void setScrubbing(ScrubbingRuntime scrubbing) {
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
    }

    public PlatformExtensionReadiness extensionReadiness() {
        return extensionReadiness;
    }

    public void tryRegisterMBeans() {
        if (mbeansRegistered) {
            return;
        }

        if (registeredAcceptedPolicy.get() == null) {
            return;
        }

        synchronized (registrationLock) {
            if (mbeansRegistered) {
                return;
            }

            registerAllOrRollback();
            mbeansRegistered = true;
        }
    }

    public void unregisterAllMBeans() {
        synchronized (registrationLock) {
            unregisterRuntimeMBeansLocked();
            unregisterDiagnosticReadinessLocked();
        }
    }

    private void unregisterRuntimeMBeansLocked() {
        batchRegistrar.unregisterSilently(DomainObjectNames.SAMPLING);
        batchRegistrar.unregisterSilently(DomainObjectNames.SCRUBBING);
        batchRegistrar.unregisterSilently(DomainObjectNames.VALIDATION);
        batchRegistrar.unregisterSilently(DomainObjectNames.EXPORT);
        batchRegistrar.unregisterSilently(DomainObjectNames.PROCESSOR_METRICS);
        batchRegistrar.unregisterSilently(DomainObjectNames.DIAGNOSTICS);
        mbeansRegistered = false;
    }

    private void unregisterDiagnosticReadinessLocked() {
        batchRegistrar.unregisterSilently(DomainObjectNames.EXTENSION_READINESS);
    }

    private void registerAllOrRollback() {
        List<MBeanBatchRegistrar.Registration> registrations = DomainMBeanFactory.build(
                slots,
                registeredAcceptedPolicy.get(),
                sharedInvalidConfigCounter,
                agentInstanceId
        );
        batchRegistrar.registerAllOrRollback(registrations);
    }

    private void registerReadinessMBean(Supplier<ExtensionReadinessWireSnapshot> projection) {
        try {
            batchRegistrar.replaceExisting(
                    new ExtensionReadinessDynamicMBean(projection),
                    DomainObjectNames.EXTENSION_READINESS
            );
        } catch (Exception e) {
            extensionReadiness.fail("READINESS_MBEAN_REGISTRATION_FAILED", e);
            throw new JmxRegistrationException("Extension readiness MBean registration failed: " + e.getMessage(), e);
        }
    }

    private final class DiagnosticReadinessRegistration implements AutoCloseable {

        private final long token;
        private boolean closed;

        private DiagnosticReadinessRegistration(long token) {
            this.token = token;
        }

        @Override
        public void close() {
            synchronized (registrationLock) {
                if (closed) {
                    return;
                }
                closed = true;
                if (activeDiagnosticGeneration != token) {
                    return;
                }
                unregisterDiagnosticReadinessLocked();
                activeDiagnosticGeneration = 0L;
            }
        }
    }

    private final class RuntimeControlsRegistration implements AutoCloseable {

        private final long token;
        private final TracingSettingsSource previous;
        private boolean closed;

        private RuntimeControlsRegistration(long token, TracingSettingsSource previous) {
            this.token = token;
            this.previous = previous;
        }

        @Override
        public void close() {
            synchronized (registrationLock) {
                if (closed) {
                    return;
                }
                closed = true;

                if (activeRuntimeGeneration != token) {
                    return;
                }

                unregisterRuntimeMBeansLocked();
                registeredAcceptedPolicy.set(previous);
                activeRuntimeGeneration = 0L;
            }
        }
    }
}
