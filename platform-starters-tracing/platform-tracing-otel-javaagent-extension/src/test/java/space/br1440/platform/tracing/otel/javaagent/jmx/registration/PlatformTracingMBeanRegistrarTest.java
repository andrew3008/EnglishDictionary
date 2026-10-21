package space.br1440.platform.tracing.otel.javaagent.jmx.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSourceTestFixtures.boundSampling;
import static space.br1440.platform.tracing.otel.javaagent.jmx.registration.JmxRegistrationTestFixtures.initializingReadinessProjection;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.management.InstanceAlreadyExistsException;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanRegistrationException;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.NotCompliantMBeanException;
import javax.management.ObjectInstance;
import javax.management.ObjectName;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import space.br1440.platform.tracing.otel.javaagent.control.CategoryBSamplingAccess;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSourceTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.DomainObjectNames;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;
import space.br1440.platform.tracing.otel.javaagent.processor.ValidatingSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionLifecycle;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionReadinessSnapshot;
import space.br1440.platform.tracing.otel.javaagent.sampler.CompositeSampler;

@SuppressWarnings("resource")
class PlatformTracingMBeanRegistrarTest {

    private final List<AutoCloseable> registrations = new ArrayList<>();
    private MBeanServer server;
    private AgentInstanceId agentInstanceId;

    @BeforeEach
    void setUp() {
        server = MBeanServerFactory.createMBeanServer();
        agentInstanceId = AgentInstanceId.random();
    }

    @AfterEach
    void cleanup() {
        for (int index = registrations.size() - 1; index >= 0; index--) {
            try {
                registrations.get(index).close();
            } catch (Exception ignored) {
            }
        }

        MBeanServerFactory.releaseMBeanServer(server);
    }

    @Test
    void authorizedRuntimeRegistrationRegistersDomainMBeans() {
        // given
        TracingSettingsSource holder = boundSampling(agentInstanceId, true, Map.of(), 0.5);
        PlatformTracingMBeanRegistrar registrar = registrar();

        // when
        wireRequiredComponents(registrar, holder);

        // then
        assertThat(server.isRegistered(DomainObjectNames.SAMPLING)).isTrue();
        assertThat(server.isRegistered(DomainObjectNames.SCRUBBING)).isTrue();
        assertThat(server.isRegistered(DomainObjectNames.VALIDATION)).isTrue();
        assertThat(server.isRegistered(DomainObjectNames.EXPORT)).isTrue();
        assertThat(server.isRegistered(DomainObjectNames.PROCESSOR_METRICS)).isTrue();
        assertThat(server.isRegistered(DomainObjectNames.DIAGNOSTICS)).isTrue();
        assertThat(server.isRegistered(DomainObjectNames.SETTINGS_STATUS)).isTrue();
        assertThat(server.isRegistered(DomainObjectNames.SETTINGS_MUTATION)).isTrue();
    }

    @Test
    void isolatedRegistrationDoesNotLeakDomainMBeansOntoThePlatformServer() {
        // given
        MBeanServer platform = ManagementFactory.getPlatformMBeanServer();
        boolean platformHadScrubbing = platform.isRegistered(DomainObjectNames.SCRUBBING);
        TracingSettingsSource holder = boundSampling(agentInstanceId, true, Map.of(), 0.5);

        // when
        wireRequiredComponents(registrar(), holder);

        // then
        assertThat(server.isRegistered(DomainObjectNames.SCRUBBING)).isTrue();
        assertThat(platform.isRegistered(DomainObjectNames.SCRUBBING)).isEqualTo(platformHadScrubbing);
    }

    @Test
    void runtimeMBeansRemainAbsentBeforeAuthorizedRegistration() {
        // given
        PlatformTracingMBeanRegistrar registrar = registrar();
        TracingSettingsSource holder = boundSampling(agentInstanceId, true, Map.of(), 0.5);

        // when
        wireComponentsExceptAcceptedPolicy(registrar, holder);

        // then
        assertThat(server.isRegistered(DomainObjectNames.SAMPLING)).isFalse();
    }

    @Test
    void runtimeRegistrationRejectsDifferentAgentInstanceId() {
        // given
        TracingSettingsSource holder = TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random());
        PlatformTracingMBeanRegistrar registrar = new PlatformTracingMBeanRegistrar(server, AgentInstanceId.random());

        // when
        assertThatThrownBy(() -> registrar.registerRuntimeControls(holder))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("JMX registrar and tracing settings agentInstanceId values must match");
    }

    @Test
    void activeRuntimeRegistrationRejectsRepeatedAuthorizedCall() {
        // given
        TracingSettingsSource holder = boundSampling(agentInstanceId, true, Map.of(), 0.5);
        PlatformTracingMBeanRegistrar registrar = registrar();
        wireRequiredComponents(registrar, holder);

        // when/then
        assertThatThrownBy(() -> registrar.registerRuntimeControls(holder))
                .isInstanceOf(IllegalStateException.class);
        assertThat(server.isRegistered(DomainObjectNames.SAMPLING)).isTrue();
    }

    @Test
    void secondRegistrarFailsClosedOnExistingOwnedObjectNames() {
        // given
        TracingSettingsSource holder = boundSampling(agentInstanceId, true, Map.of(), 0.5);
        PlatformTracingMBeanRegistrar first = registrar();
        wireRequiredComponents(first, holder);
        PlatformTracingMBeanRegistrar second = registrar();

        // when
        JmxRegistrationException failure = catchThrowableOfType(
                JmxRegistrationException.class,
                () -> wireRequiredComponents(second, holder)
        );

        // then
        assertThat(failure).hasMessageContaining("JMX ObjectName ownership collision");
        assertThat(failure.failureCode()).isNull();
        assertThat(second.extensionReadiness().snapshot().lifecycle())
                .isEqualTo(PlatformExtensionLifecycle.INITIALIZING);
        assertThat(server.isRegistered(DomainObjectNames.SAMPLING)).isTrue();
    }

    @Test
    void closingRuntimeRegistrationRemovesCompleteRuntimeBatch() {
        // given
        TracingSettingsSource holder = boundSampling(agentInstanceId, true, Map.of(), 0.5);
        PlatformTracingMBeanRegistrar registrar = registrar();
        PlatformTracingMBeanRegistrar.RuntimeRegistration runtimeRegistration = wireRequiredComponents(registrar, holder);
        assertThat(server.isRegistered(DomainObjectNames.SAMPLING)).isTrue();

        // when
        runtimeRegistration.close();

        // then
        assertThat(server.isRegistered(DomainObjectNames.SAMPLING)).isFalse();
        assertThat(server.isRegistered(DomainObjectNames.SCRUBBING)).isFalse();
        assertThat(server.isRegistered(DomainObjectNames.VALIDATION)).isFalse();
        assertThat(server.isRegistered(DomainObjectNames.EXPORT)).isFalse();
        assertThat(server.isRegistered(DomainObjectNames.PROCESSOR_METRICS)).isFalse();
        assertThat(server.isRegistered(DomainObjectNames.DIAGNOSTICS)).isFalse();
        assertThat(server.isRegistered(DomainObjectNames.SETTINGS_STATUS)).isFalse();
        assertThat(server.isRegistered(DomainObjectNames.SETTINGS_MUTATION)).isFalse();
    }

    @Test
    void closingRuntimeRegistrationTwiceIsIdempotentAndReleasesTheGeneration() {
        // given
        TracingSettingsSource holder = boundSampling(agentInstanceId, true, Map.of(), 0.5);
        PlatformTracingMBeanRegistrar registrar = registrar();
        PlatformTracingMBeanRegistrar.RuntimeRegistration runtimeRegistration = wireRequiredComponents(registrar, holder);
        runtimeRegistration.close();

        // when
        runtimeRegistration.close();

        // then
        assertThat(server.isRegistered(DomainObjectNames.SAMPLING)).isFalse();
        PlatformTracingMBeanRegistrar.RuntimeRegistration reregistration = trackRegistration(registrar.registerRuntimeControls(holder));
        assertThat(server.isRegistered(DomainObjectNames.SAMPLING)).isTrue();
        assertThat(reregistration.batchId()).isNotEqualTo(runtimeRegistration.batchId());

        runtimeRegistration.close();
        assertThat(server.isRegistered(DomainObjectNames.SAMPLING)).isTrue();
    }

    @Test
    void concurrentAuthorizedCallsDoNotBreakActiveRegistration() throws Exception {
        // given
        TracingSettingsSource holder = boundSampling(agentInstanceId, true, Map.of(), 0.5);
        PlatformTracingMBeanRegistrar registrar = registrar();
        wireRequiredComponents(registrar, holder);

        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    registrar.registerRuntimeControls(holder);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (IllegalStateException expectedActiveRegistration) {
                    // Активную generation может зарегистрировать только один конкурентный вызов.
                } finally {
                    done.countDown();
                }
            });
        }

        // when
        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        // then
        assertThat(server.isRegistered(DomainObjectNames.SAMPLING)).isTrue();
        assertThat(server.isRegistered(DomainObjectNames.DIAGNOSTICS)).isTrue();
    }

    @Test
    void exactlyOneConcurrentCallerRegistersCompleteRuntimeBatch() throws Exception {
        // given
        AtomicInteger registerCallCount = new AtomicInteger();
        MBeanServer countingSpy = new DelegatingMBeanServer(server) {
            @Override
            public ObjectInstance registerMBean(Object object, ObjectName name)
                    throws InstanceAlreadyExistsException,
                    MBeanRegistrationException,
                    NotCompliantMBeanException {
                registerCallCount.incrementAndGet();
                return super.registerMBean(object, name);
            }
        };

        TracingSettingsSource holder = boundSampling(agentInstanceId, true, Map.of(), 0.5);
        PlatformTracingMBeanRegistrar registrar =
                new PlatformTracingMBeanRegistrar(countingSpy, holder.current().agentInstanceId());
        wireComponentsExceptAcceptedPolicy(registrar, holder);

        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger successCount = new AtomicInteger();
        AtomicReference<PlatformTracingMBeanRegistrar.RuntimeRegistration> successfulRegistration = new AtomicReference<>();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    successfulRegistration.set(registrar.registerRuntimeControls(holder));
                    successCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (IllegalStateException expectedReBindRejection) {
                    // Активную generation может зарегистрировать только один конкурентный вызов.
                } finally {
                    done.countDown();
                }
            });
        }

        // when
        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        // then
        assertThat(successCount.get())
                .as("ровно один вызов registerRuntimeControls должен выиграть generation-scoped регистрацию")
                .isEqualTo(1);
        assertThat(registerCallCount.get())
                .as("readiness и восемь runtime MBeans должны зарегистрироваться ровно один раз")
                .isEqualTo(9);
        assertThat(successfulRegistration.get()).isNotNull();
        trackRegistration(successfulRegistration.get());
    }

    @Test
    @ResourceLock(AgentLogCapture.RESOURCE_LOCK)
    void rollback_addSuppressed_present_when_rollback_fails() {
        // given
        final int[] registerCallCount = {0};
        AtomicBoolean failureLoggedBeforeRollback = new AtomicBoolean();

        try (AgentLogCapture logs = AgentLogCapture.installed()) {
            MBeanServer faultySpy = new DelegatingMBeanServer(server) {
                @Override
                public ObjectInstance registerMBean(Object object, ObjectName name)
                        throws InstanceAlreadyExistsException,
                        MBeanRegistrationException,
                        NotCompliantMBeanException {
                    registerCallCount[0]++;
                    if (registerCallCount[0] == 5) {
                        throw new NotCompliantMBeanException("simulated failure at domain 4");
                    }
                    return super.registerMBean(object, name);
                }

                @Override
                public void unregisterMBean(ObjectName name)
                        throws InstanceNotFoundException,
                        MBeanRegistrationException {
                    if (DomainObjectNames.SCRUBBING.equals(name)) {
                        failureLoggedBeforeRollback.set(logs.events().stream()
                                .anyMatch(event -> "MBEAN_BATCH_REGISTRATION_FAILED".equals(event.eventName())));
                        throw new InstanceNotFoundException("simulated rollback failure");
                    }
                    super.unregisterMBean(name);
                }
            };

            TracingSettingsSource holder = boundSampling(agentInstanceId, true, Map.of(), 0.5);
            PlatformTracingMBeanRegistrar registrar =
                    new PlatformTracingMBeanRegistrar(faultySpy, holder.current().agentInstanceId());
            wireComponentsExceptAcceptedPolicy(registrar, holder);

            // when
            JmxRegistrationException ex = catchThrowableOfType(
                    JmxRegistrationException.class,
                    () -> registrar.registerRuntimeControls(holder)
            );

            // then
            assertThat(ex.getCause()).isNotNull();
            assertThat(ex.getSuppressed()).isNotEmpty();
            assertThat(ex.failureCode()).isEqualTo("JMX_BATCH_REGISTRATION_FAILED");
            assertThat(ex.rollbackIncomplete()).isTrue();
            assertThat(registrar.extensionReadiness().snapshot())
                    .isInstanceOf(PlatformExtensionReadinessSnapshot.Initializing.class);
            assertThat(failureLoggedBeforeRollback).isFalse();
            assertThat(logs.events())
                    .filteredOn(event -> "MBEAN_BATCH_REGISTRATION_FAILED".equals(event.eventName()))
                    .hasSize(1);
            assertThat(logs.rendered()).doesNotContain("simulated rollback failure");
        }
    }

    @Test
    void fatalRuntimeRegistrationFailureRollsBackBatchWithoutMutatingReadiness() {
        // given
        NoClassDefFoundError fatalFailure = new NoClassDefFoundError("simulated runtime MBean linkage failure");
        MBeanServer failingServer = new DelegatingMBeanServer(server) {

            @Override
            public ObjectInstance registerMBean(Object object, ObjectName name)
                    throws InstanceAlreadyExistsException,
                    MBeanRegistrationException,
                    NotCompliantMBeanException {
                if (DomainObjectNames.VALIDATION.equals(name)) {
                    throw fatalFailure;
                }

                return super.registerMBean(object, name);
            }
        };

        TracingSettingsSource holder = boundSampling(agentInstanceId, true, Map.of(), 0.5);
        PlatformTracingMBeanRegistrar registrar = new PlatformTracingMBeanRegistrar(
                failingServer,
                holder.current().agentInstanceId()
        );
        wireComponentsExceptAcceptedPolicy(registrar, holder);

        // when
        assertThatThrownBy(() -> registrar.registerRuntimeControls(holder))
                .isSameAs(fatalFailure);

        // then
        assertThat(server.isRegistered(DomainObjectNames.SAMPLING)).isFalse();
        assertThat(server.isRegistered(DomainObjectNames.SCRUBBING)).isFalse();
        assertThat(server.isRegistered(DomainObjectNames.VALIDATION)).isFalse();
        assertThat(server.isRegistered(DomainObjectNames.EXPORT)).isFalse();
        assertThat(server.isRegistered(DomainObjectNames.PROCESSOR_METRICS)).isFalse();
        assertThat(server.isRegistered(DomainObjectNames.DIAGNOSTICS)).isFalse();
        assertThat(server.isRegistered(DomainObjectNames.SETTINGS_STATUS)).isFalse();
        assertThat(server.isRegistered(DomainObjectNames.SETTINGS_MUTATION)).isFalse();

        assertThat(registrar.extensionReadiness().snapshot())
                .isInstanceOf(PlatformExtensionReadinessSnapshot.Initializing.class);
    }

    @Test
    void runtimeCloseFailureReleasesGenerationAndLeavesLeftoverForScopeReclaim() throws Exception {
        // given
        AtomicBoolean failSamplingUnregisterOnce = new AtomicBoolean();
        MBeanServer faultySpy = new DelegatingMBeanServer(server) {
            @Override
            public void unregisterMBean(ObjectName name)
                    throws InstanceNotFoundException,
                    MBeanRegistrationException {
                if (DomainObjectNames.SAMPLING.equals(name) && failSamplingUnregisterOnce.compareAndSet(true, false)) {
                    throw new InstanceNotFoundException("simulated cleanup failure");
                }
                super.unregisterMBean(name);
            }
        };
        TracingSettingsSource holder = boundSampling(agentInstanceId, true, Map.of(), 0.5);
        PlatformTracingMBeanRegistrar registrar = new PlatformTracingMBeanRegistrar(faultySpy, agentInstanceId);
        AutoCloseable diagnostic = registrar.registerDiagnosticReadiness(initializingReadinessProjection(agentInstanceId));
        wireOwners(registrar, holder);
        PlatformTracingMBeanRegistrar.RuntimeRegistration runtime = registrar.registerRuntimeControls(holder);
        failSamplingUnregisterOnce.set(true);

        // when
        JmxRegistrationException failure = catchThrowableOfType(JmxRegistrationException.class, runtime::close);

        // then
        assertThat(failure).isNotNull();
        assertThat(failure.rollbackIncomplete()).isTrue();
        assertThat(server.isRegistered(DomainObjectNames.SAMPLING)).isTrue();
        assertThat(server.isRegistered(DomainObjectNames.SCRUBBING)).isFalse();
        assertThatCode(runtime::close).doesNotThrowAnyException();

        diagnostic.close();
        assertThat(server.isRegistered(DomainObjectNames.SAMPLING))
                .as("scope reclaim on the next serialized release removes the leftover")
                .isFalse();
        trackRegistration(registrar.registerRuntimeControls(holder));
        assertThat(server.isRegistered(DomainObjectNames.SAMPLING)).isTrue();
    }

    @Test
    void batchRegistrationFailureKeepsIncompleteRollbackLeftoverUntilScopeReclaim() throws Exception {
        // given
        AtomicBoolean failScrubbingUnregisterOnce = new AtomicBoolean(true);
        MBeanServer faultySpy = new DelegatingMBeanServer(server) {
            @Override
            public ObjectInstance registerMBean(Object object, ObjectName name)
                    throws InstanceAlreadyExistsException,
                    MBeanRegistrationException,
                    NotCompliantMBeanException {
                if (DomainObjectNames.VALIDATION.equals(name)) {
                    throw new NotCompliantMBeanException("simulated registration failure");
                }
                return super.registerMBean(object, name);
            }

            @Override
            public void unregisterMBean(ObjectName name)
                    throws InstanceNotFoundException,
                    MBeanRegistrationException {
                if (DomainObjectNames.SCRUBBING.equals(name) && failScrubbingUnregisterOnce.compareAndSet(true, false)) {
                    throw new InstanceNotFoundException("simulated rollback failure");
                }
                super.unregisterMBean(name);
            }
        };
        TracingSettingsSource holder = boundSampling(agentInstanceId, true, Map.of(), 0.5);
        PlatformTracingMBeanRegistrar registrar = new PlatformTracingMBeanRegistrar(faultySpy, agentInstanceId);
        AutoCloseable diagnostic = registrar.registerDiagnosticReadiness(initializingReadinessProjection(agentInstanceId));
        wireOwners(registrar, holder);

        // when
        JmxRegistrationException failure = catchThrowableOfType(
                JmxRegistrationException.class,
                () -> registrar.registerRuntimeControls(holder)
        );

        // then
        assertThat(failure.failureCode()).isEqualTo("JMX_BATCH_REGISTRATION_FAILED");
        assertThat(failure.rollbackIncomplete()).isTrue();
        assertThat(server.isRegistered(DomainObjectNames.SAMPLING)).isFalse();
        assertThat(server.isRegistered(DomainObjectNames.SCRUBBING))
                .as("failed registration reports the leftover instead of reclaiming it in the same call")
                .isTrue();

        diagnostic.close();
        assertThat(server.isRegistered(DomainObjectNames.SCRUBBING)).isFalse();
    }

    private PlatformTracingMBeanRegistrar.RuntimeRegistration wireRequiredComponents(PlatformTracingMBeanRegistrar registrar,
                                                                                     TracingSettingsSource acceptedPolicy) {
        wireComponentsExceptAcceptedPolicy(registrar, acceptedPolicy);
        return trackRegistration(registrar.registerRuntimeControls(acceptedPolicy));
    }

    private void wireComponentsExceptAcceptedPolicy(PlatformTracingMBeanRegistrar registrar,
                                                    TracingSettingsSource acceptedPolicy) {
        trackRegistration(registrar.registerDiagnosticReadiness(
                initializingReadinessProjection(registrar.agentInstanceId()))
        );
        wireOwners(registrar, acceptedPolicy);
    }

    private static void wireOwners(PlatformTracingMBeanRegistrar registrar, TracingSettingsSource acceptedPolicy) {
        CompositeSampler sampler = new CompositeSampler(
                acceptedPolicy,
                CategoryBSamplingAccess.snapshot(List.of(), List.of())
        );
        ValidatingSpanProcessor validating = new ValidatingSpanProcessor(acceptedPolicy);

        registrar.setCompositeSampler(sampler);
        registrar.setValidating(validating);
    }

    private PlatformTracingMBeanRegistrar registrar() {
        return new PlatformTracingMBeanRegistrar(server, agentInstanceId);
    }

    private <T extends AutoCloseable> T trackRegistration(T registration) {
        registrations.add(registration);
        return registration;
    }
}
