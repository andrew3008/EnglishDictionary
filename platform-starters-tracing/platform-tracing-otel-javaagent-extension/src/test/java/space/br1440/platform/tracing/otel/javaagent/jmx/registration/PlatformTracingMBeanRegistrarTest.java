package space.br1440.platform.tracing.otel.javaagent.jmx.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static space.br1440.platform.tracing.otel.javaagent.jmx.registration.JmxRegistrationTestFixtures.initializingReadinessProjection;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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

import space.br1440.platform.tracing.otel.javaagent.control.CategoryBSamplingAccess;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.jmx.DomainObjectNames;
import space.br1440.platform.tracing.otel.javaagent.processor.ValidatingSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionLifecycle;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionReadinessSnapshot;
import space.br1440.platform.tracing.otel.javaagent.sampler.CompositeSampler;

@SuppressWarnings("resource")
class PlatformTracingMBeanRegistrarTest {

    private final List<AutoCloseable> registrations = new ArrayList<>();
    private MBeanServer server;

    @BeforeEach
    void setUp() {
        server = MBeanServerFactory.createMBeanServer();
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
        TracingSettingsSource holder = TracingSettingsSource.isolatedSampling(true, Map.of(), 0.5);
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
        TracingSettingsSource holder = TracingSettingsSource.isolatedSampling(true, Map.of(), 0.5);

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
        TracingSettingsSource holder = TracingSettingsSource.isolatedSampling(true, Map.of(), 0.5);

        // when
        wireComponentsExceptAcceptedPolicy(registrar, holder);

        // then
        assertThat(server.isRegistered(DomainObjectNames.SAMPLING)).isFalse();
    }

    @Test
    void activeRuntimeRegistrationRejectsRepeatedAuthorizedCall() {
        // given
        TracingSettingsSource holder = TracingSettingsSource.isolatedSampling(true, Map.of(), 0.5);
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
        TracingSettingsSource holder = TracingSettingsSource.isolatedSampling(true, Map.of(), 0.5);
        PlatformTracingMBeanRegistrar first = registrar();
        wireRequiredComponents(first, holder);
        PlatformTracingMBeanRegistrar second = registrar();

        // when/then
        assertThatThrownBy(() -> wireRequiredComponents(second, holder))
                .isInstanceOf(JmxRegistrationException.class)
                .hasMessageContaining("JMX ObjectName ownership collision");
        assertThat(server.isRegistered(DomainObjectNames.SAMPLING)).isTrue();
    }

    @Test
    void closingRuntimeRegistrationRemovesCompleteRuntimeBatch() {
        // given
        TracingSettingsSource holder = TracingSettingsSource.isolatedSampling(true, Map.of(), 0.5);
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
        TracingSettingsSource holder = TracingSettingsSource.isolatedSampling(true, Map.of(), 0.5);
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
        TracingSettingsSource holder = TracingSettingsSource.isolatedSampling(true, Map.of(), 0.5);
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

        TracingSettingsSource holder = TracingSettingsSource.isolatedSampling(true, Map.of(), 0.5);
        PlatformTracingMBeanRegistrar registrar = new PlatformTracingMBeanRegistrar(countingSpy);
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
    void rollback_addSuppressed_present_when_rollback_fails() {
        // given
        final int[] registerCallCount = {0};
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
                    throw new InstanceNotFoundException("simulated rollback failure");
                }
                super.unregisterMBean(name);
            }
        };

        TracingSettingsSource holder = TracingSettingsSource.isolatedSampling(true, Map.of(), 0.5);
        PlatformTracingMBeanRegistrar registrar = new PlatformTracingMBeanRegistrar(faultySpy);
        wireComponentsExceptAcceptedPolicy(registrar, holder);

        // when
        try {
            registrar.registerRuntimeControls(holder);
        } catch (JmxRegistrationException ex) {
            // then
            assertThat(ex.getCause()).isNotNull();
            assertThat(ex.getSuppressed()).isNotEmpty();
            assertThat(ex.rollbackIncomplete()).isTrue();
            return;
        }
        assertThat(registerCallCount[0]).isGreaterThanOrEqualTo(0);
    }

    @Test
    void fatalRuntimeRegistrationFailureRollsBackBatchAndFailsReadiness() {
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
        TracingSettingsSource holder = TracingSettingsSource.isolatedSampling(true, Map.of(), 0.5);
        PlatformTracingMBeanRegistrar registrar = new PlatformTracingMBeanRegistrar(failingServer);
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
        PlatformExtensionReadinessSnapshot readiness = registrar.extensionReadiness().snapshot();
        assertThat(readiness.lifecycle())
                .isEqualTo(PlatformExtensionLifecycle.FAILED);
        assertThat(readiness.failureCode())
                .isEqualTo("JMX_BATCH_REGISTRATION_FAILED");
    }

    private PlatformTracingMBeanRegistrar.RuntimeRegistration wireRequiredComponents(
            PlatformTracingMBeanRegistrar registrar,
            TracingSettingsSource acceptedPolicy) {
        wireComponentsExceptAcceptedPolicy(registrar, acceptedPolicy);
        return trackRegistration(registrar.registerRuntimeControls(acceptedPolicy));
    }

    private void wireComponentsExceptAcceptedPolicy(PlatformTracingMBeanRegistrar registrar,
                                                    TracingSettingsSource acceptedPolicy) {
        trackRegistration(registrar.registerDiagnosticReadiness(initializingReadinessProjection()));
        CompositeSampler sampler = new CompositeSampler(
                acceptedPolicy, CategoryBSamplingAccess.snapshot(List.of(), List.of()));
        ValidatingSpanProcessor validating = new ValidatingSpanProcessor(acceptedPolicy);

        registrar.setCompositeSampler(sampler);
        registrar.setValidating(validating);
    }

    private PlatformTracingMBeanRegistrar registrar() {
        return new PlatformTracingMBeanRegistrar(server);
    }

    private <T extends AutoCloseable> T trackRegistration(T registration) {
        registrations.add(registration);
        return registration;
    }
}
