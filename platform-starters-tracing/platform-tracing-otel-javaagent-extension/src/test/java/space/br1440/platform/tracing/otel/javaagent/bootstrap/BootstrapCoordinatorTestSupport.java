package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.management.JMException;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.NotCompliantMBeanException;
import javax.management.timer.Timer;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;

import space.br1440.platform.tracing.otel.javaagent.bootstrap.resources.ShutdownHookRegistry;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.session.AutoconfigureSession;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.verification.ExtensionRuntimeVerifier;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.DomainObjectNames;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionReadinessWireSnapshot;
import space.br1440.platform.tracing.otel.javaagent.jmx.registration.PlatformTracingMBeanRegistrar;
import space.br1440.platform.tracing.otel.javaagent.jmx.security.RemoteJmxAssessorTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.jmx.security.RemoteJmxMutationRegistrationAssessor;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionCapability;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.assembly.PlatformScrubberFactory;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntime;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.trace.SdkTracerProvider;

final class BootstrapCoordinatorTestSupport {

    static final String WIRE_INITIALIZING = "INITIALIZING";
    static final String WIRE_READY = "READY";
    static final String WIRE_FAILED = "FAILED";

    static final long CLOSE_FAILURE_EVIDENCE_GENERATION = 2L;

    private BootstrapCoordinatorTestSupport() {
    }

    static CoordinatorBuilder coordinator() {
        return new CoordinatorBuilder();
    }

    static void assertBootstrapFailure(ThrowingCallable call, String expectedCode) {
        assertThat(catchBootstrapFailure(call, expectedCode).failure().effectiveCode())
                .as("failure code")
                .isEqualTo(expectedCode);
    }

    static BootstrapException catchBootstrapFailure(ThrowingCallable call, String expectedCode) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).as("BootstrapException with code %s", expectedCode)
                .isInstanceOf(BootstrapException.class);
        BootstrapException failure = (BootstrapException) thrown;
        assertThat(failure.failure().effectiveCode()).as("failure code").isEqualTo(expectedCode);
        return failure;
    }

    static void registerForeignReadinessMBean(MBeanServer server) throws JMException {
        server.registerMBean(new Timer(), DomainObjectNames.EXTENSION_READINESS);
    }

    static void satisfyReadinessBarrier(AutoconfigureSession session) {
        markCapabilitiesExcept(session, EnumSet.of(PlatformExtensionCapability.PLATFORM_SAMPLER_INSTALLED));
        acknowledgeScrubbingExcept(session, EnumSet.noneOf(ScrubbingAcknowledgement.class));
    }

    static void markCapabilitiesExcept(AutoconfigureSession session, Set<PlatformExtensionCapability> excluded) {
        for (PlatformExtensionCapability capability : PlatformExtensionCapability.values()) {
            if (!excluded.contains(capability)) {
                session.readiness().markInstalled(capability);
            }
        }
    }

    static void acknowledgeScrubbingExcept(AutoconfigureSession session, Set<ScrubbingAcknowledgement> excluded) {
        for (ScrubbingAcknowledgement acknowledgement : ScrubbingAcknowledgement.values()) {
            if (!excluded.contains(acknowledgement)) {
                acknowledgement.applyTo(session.scrubbingRuntime());
            }
        }
    }

    static SdkTracerProvider providerFor(AutoconfigureSession session) {
        return SdkTracerProvider.builder()
                .setSampler(session.sampler())
                .build();
    }

    static ConfigProperties config() {
        return config(Map.of());
    }

    static ConfigProperties config(Map<String, String> data) {
        return config(data, Map.of());
    }

    static ConfigProperties config(Map<String, String> data, Map<String, String> routeRatios) {
        Map<String, String> merged = new HashMap<>(data);
        merged.putIfAbsent("otel.traces.sampler", "always_off");
        return new MapConfigProperties(merged, new HashMap<>(routeRatios));
    }

    static ConfigProperties invalidValidationLevelConfig() {
        return config(Map.of(ExtensionPropertyNames.SPAN_ATTRIBUTES_VALIDATION_LEVEL, "STRICT"));
    }

    static ConfigProperties emptyBuiltInRulesConfig() {
        return config(Map.of(ExtensionPropertyNames.SCRUBBING_BUILT_IN_RULES, ""));
    }

    static MBeanServer recordingReadinessRegistration(AtomicReference<BootstrapCoordinator> coordinatorReference,
                                                      List<String> observations) {
        MBeanServer delegate = MBeanServerFactory.newMBeanServer();
        return (MBeanServer) Proxy.newProxyInstance(
                BootstrapCoordinatorTestSupport.class.getClassLoader(),
                new Class<?>[] {MBeanServer.class},
                (proxy, method, arguments) -> {
                    if ("registerMBean".equals(method.getName())
                            && arguments != null
                            && arguments.length == 2
                            && DomainObjectNames.EXTENSION_READINESS.equals(arguments[1])) {
                        BootstrapCoordinator coordinator = coordinatorReference.get();
                        ExtensionReadinessWireSnapshot wire = coordinator.currentWire();
                        observations.add("%s/%s/%s".formatted(
                                coordinator.state(),
                                wire.lifecycle(),
                                wire.failureCode()
                        ));
                    }

                    try {
                        return method.invoke(delegate, arguments);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                }
        );
    }

    static MBeanServer failingRuntimeBatchRegistration() {
        MBeanServer delegate = MBeanServerFactory.newMBeanServer();
        return (MBeanServer) Proxy.newProxyInstance(
                BootstrapCoordinatorTestSupport.class.getClassLoader(),
                new Class<?>[] {MBeanServer.class},
                (proxy, method, arguments) -> {
                    if ("registerMBean".equals(method.getName())
                            && arguments != null
                            && arguments.length == 2
                            && DomainObjectNames.SAMPLING.equals(arguments[1])) {
                        throw new NotCompliantMBeanException("simulated runtime batch registration failure");
                    }

                    try {
                        return method.invoke(delegate, arguments);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                }
        );
    }

    enum ScrubbingAcknowledgement {
        PROCESSOR_INSTALLED(ScrubbingRuntime::acknowledgeProcessorInstalled),
        EXPORT_MODE_RESOLVED(ScrubbingRuntime::acknowledgeExportModeResolved),
        EXPORTER_WRAPPED(ScrubbingRuntime::acknowledgeExporterWrapped),
        EXPORT_PATH_PROTECTED(ScrubbingRuntime::acknowledgeExportPathProtected);

        private final Consumer<ScrubbingRuntime> action;

        ScrubbingAcknowledgement(Consumer<ScrubbingRuntime> action) {
            this.action = action;
        }

        void applyTo(ScrubbingRuntime runtime) {
            action.accept(runtime);
        }
    }

    static final class CoordinatorBuilder {

        private MBeanServer server = MBeanServerFactory.newMBeanServer();
        private AgentInstanceId agentInstanceId = AgentInstanceId.random();
        private ShutdownHookRegistry hooks = new CapturingShutdownHookRegistry();
        private ExtensionRuntimeVerifier verifier = RuntimeEvidenceTestFixtures.trustedVerifier();
        private PlatformScrubberFactory scrubberFactory = new PlatformScrubberFactory();
        private RemoteJmxMutationRegistrationAssessor assessor = RemoteJmxAssessorTestFixtures.allowingAssessor();
        private final DiagnosticReadinessHandle diagnosticHandle = new DiagnosticReadinessHandle();
        private PlatformTracingMBeanRegistrar registrar;

        private CoordinatorBuilder() {
        }

        CoordinatorBuilder server(MBeanServer server) {
            this.server = server;
            return this;
        }

        CoordinatorBuilder agentInstanceId(AgentInstanceId agentInstanceId) {
            this.agentInstanceId = agentInstanceId;
            return this;
        }

        CoordinatorBuilder hooks(ShutdownHookRegistry hooks) {
            this.hooks = hooks;
            return this;
        }

        CoordinatorBuilder verifier(ExtensionRuntimeVerifier verifier) {
            this.verifier = verifier;
            return this;
        }

        CoordinatorBuilder scrubberFactory(PlatformScrubberFactory scrubberFactory) {
            this.scrubberFactory = scrubberFactory;
            return this;
        }

        CoordinatorBuilder assessor(RemoteJmxMutationRegistrationAssessor assessor) {
            this.assessor = assessor;
            return this;
        }

        CoordinatorBuilder registrar(PlatformTracingMBeanRegistrar registrar) {
            this.registrar = registrar;
            return this;
        }

        BootstrapCoordinator build() {
            RemoteJmxMutationRegistrationAssessor configuredAssessor = assessor;
            PlatformTracingMBeanRegistrar configuredRegistrar = registrar == null
                    ? new PlatformTracingMBeanRegistrar(server, agentInstanceId)
                    : registrar;
            return new BootstrapCoordinator(
                    hooks,
                    scrubberFactory,
                    verifier,
                    ignored -> configuredAssessor,
                    configuredRegistrar,
                    diagnosticHandle
            );
        }
    }

    static final class CapturingShutdownHookRegistry implements ShutdownHookRegistry {

        private final AtomicReference<Runnable> action = new AtomicReference<>();
        private final AtomicReference<String> threadName = new AtomicReference<>();
        private final AtomicInteger registrationCount = new AtomicInteger();

        @Override
        public void register(String registeredThreadName, Runnable registeredAction) {
            String requiredThreadName = Objects.requireNonNull(registeredThreadName, "registeredThreadName");
            Runnable requiredAction = Objects.requireNonNull(registeredAction, "registeredAction");
            if (!action.compareAndSet(null, requiredAction)) {
                throw new IllegalStateException("Shutdown hook is already registered");
            }
            threadName.set(requiredThreadName);
            registrationCount.incrementAndGet();
        }

        Runnable action() {
            Runnable registeredAction = action.get();
            if (registeredAction == null) {
                throw new IllegalStateException("Shutdown hook is not registered");
            }
            return registeredAction;
        }

        String threadName() {
            String registeredThreadName = threadName.get();
            if (registeredThreadName == null) {
                throw new IllegalStateException("Shutdown hook is not registered");
            }
            return registeredThreadName;
        }

        int registrationCount() {
            return registrationCount.get();
        }

        void runHook() {
            action().run();
        }
    }

    private record MapConfigProperties(Map<String, String> data,
                                       Map<String, String> routeRatios) implements ConfigProperties {

        @Override
        public String getString(String name) {
            return data.get(name);
        }

        @Override
        public Boolean getBoolean(String name) {
            String value = data.get(name);
            return value == null ? null : Boolean.parseBoolean(value);
        }

        @Override
        public Integer getInt(String name) {
            String value = data.get(name);
            return value == null ? null : Integer.valueOf(value);
        }

        @Override
        public Long getLong(String name) {
            String value = data.get(name);
            return value == null ? null : Long.valueOf(value);
        }

        @Override
        public Double getDouble(String name) {
            String value = data.get(name);
            return value == null ? null : Double.valueOf(value);
        }

        @Override
        public Duration getDuration(String name) {
            return null;
        }

        @Override
        public List<String> getList(String name) {
            String value = data.get(name);

            if (value == null) {
                return List.of();
            }

            if (value.isBlank()) {
                return List.of();
            }

            return List.of(value.split("\\s*,\\s*"));
        }

        @Override
        public Map<String, String> getMap(String name) {
            if (ExtensionPropertyNames.SAMPLING_ROUTE_RATIOS.equals(name)) {
                return routeRatios;
            }
            return Map.of();
        }
    }
}
