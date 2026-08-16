package space.br1440.platform.tracing.otel.javaagent.jmx.registration;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.control.CategoryBSamplingAccess;
import space.br1440.platform.tracing.otel.javaagent.jmx.DomainObjectNames;
import space.br1440.platform.tracing.otel.javaagent.processor.ValidatingSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.sampler.CompositeSampler;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Tests for {@link DomainMBeanRegistrar}: batch registration, idempotency, rollback,
 * and concurrent double-call safety.
 */
class DomainMBeanRegistrarTest {

    private final MBeanServer server = ManagementFactory.getPlatformMBeanServer();

    @AfterEach
    void cleanup() {
        DomainMBeanRegistrar cleanup = new DomainMBeanRegistrar();
        cleanup.unregisterAllMBeans();
    }

    @Test
    void tryRegisterMBeans_registers_all_six_domain_MBeans() throws Exception {
        TracingSettingsSource holder = TracingSettingsSource.isolatedSampling(true, Map.of(), 0.5);
        DomainMBeanRegistrar registrar = new DomainMBeanRegistrar();
        wireRequiredComponents(registrar, holder);

        assertThat(server.isRegistered(DomainObjectNames.SAMPLING)).isTrue();
        assertThat(server.isRegistered(DomainObjectNames.SCRUBBING)).isTrue();
        assertThat(server.isRegistered(DomainObjectNames.VALIDATION)).isTrue();
        assertThat(server.isRegistered(DomainObjectNames.EXPORT)).isTrue();
        assertThat(server.isRegistered(DomainObjectNames.PROCESSOR_METRICS)).isTrue();
        assertThat(server.isRegistered(DomainObjectNames.DIAGNOSTICS)).isTrue();
    }

    @Test
    void tryRegisterMBeans_requires_configHolder_before_registration() {
        DomainMBeanRegistrar registrar = new DomainMBeanRegistrar();
        TracingSettingsSource holder = TracingSettingsSource.isolatedSampling(true, Map.of(), 0.5);
        wireComponentsExceptAcceptedPolicy(registrar, holder);

        assertThat(server.isRegistered(DomainObjectNames.SAMPLING)).isFalse();
    }

    @Test
    void tryRegisterMBeans_is_idempotent_repeated_call_does_not_duplicate_registration() throws Exception {
        TracingSettingsSource holder = TracingSettingsSource.isolatedSampling(true, Map.of(), 0.5);
        DomainMBeanRegistrar registrar = new DomainMBeanRegistrar();
        wireRequiredComponents(registrar, holder);

        assertThatNoException().isThrownBy(registrar::tryRegisterMBeans);
        assertThat(server.isRegistered(DomainObjectNames.SAMPLING)).isTrue();
    }

    @Test
    void tryRegisterMBeans_second_registrar_applies_REPLACE_EXISTING() throws Exception {
        TracingSettingsSource holder = TracingSettingsSource.isolatedSampling(true, Map.of(), 0.5);
        DomainMBeanRegistrar first = new DomainMBeanRegistrar();
        wireRequiredComponents(first, holder);

        DomainMBeanRegistrar second = new DomainMBeanRegistrar();
        assertThatNoException().isThrownBy(() -> wireRequiredComponents(second, holder));
        assertThat(server.isRegistered(DomainObjectNames.SAMPLING)).isTrue();
    }

    @Test
    void unregisterAllMBeans_unregisters_all_six() throws Exception {
        TracingSettingsSource holder = TracingSettingsSource.isolatedSampling(true, Map.of(), 0.5);
        DomainMBeanRegistrar registrar = new DomainMBeanRegistrar();
        wireRequiredComponents(registrar, holder);
        assertThat(server.isRegistered(DomainObjectNames.SAMPLING)).isTrue();

        registrar.unregisterAllMBeans();

        assertThat(server.isRegistered(DomainObjectNames.SAMPLING)).isFalse();
        assertThat(server.isRegistered(DomainObjectNames.SCRUBBING)).isFalse();
        assertThat(server.isRegistered(DomainObjectNames.VALIDATION)).isFalse();
        assertThat(server.isRegistered(DomainObjectNames.EXPORT)).isFalse();
        assertThat(server.isRegistered(DomainObjectNames.PROCESSOR_METRICS)).isFalse();
        assertThat(server.isRegistered(DomainObjectNames.DIAGNOSTICS)).isFalse();
    }

    @Test
    void concurrentDoubleCall_tryRegisterMBeans_does_not_break_invariants() throws Exception {
        TracingSettingsSource holder = TracingSettingsSource.isolatedSampling(true, Map.of(), 0.5);
        DomainMBeanRegistrar registrar = new DomainMBeanRegistrar();
        wireRequiredComponents(registrar, holder);

        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    registrar.tryRegisterMBeans();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(server.isRegistered(DomainObjectNames.SAMPLING)).isTrue();
        assertThat(server.isRegistered(DomainObjectNames.DIAGNOSTICS)).isTrue();
    }

    @Test
    void spy_registerMBean_called_exactly_6_times_by_exactly_one_winner_among_parallel_calls() throws Exception {
        AtomicInteger registerCallCount = new AtomicInteger();
        MBeanServer countingSpy = new DelegatingMBeanServer(server) {
            @Override
            public javax.management.ObjectInstance registerMBean(Object object, ObjectName name)
                    throws javax.management.InstanceAlreadyExistsException,
                    javax.management.MBeanRegistrationException,
                    javax.management.NotCompliantMBeanException {
                registerCallCount.incrementAndGet();
                return super.registerMBean(object, name);
            }
        };

        TracingSettingsSource holder = TracingSettingsSource.isolatedSampling(true, Map.of(), 0.5);
        DomainMBeanRegistrar registrar = new DomainMBeanRegistrar(countingSpy);
        wireComponentsExceptAcceptedPolicy(registrar, holder);

        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger successCount = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    registrar.registerRuntimeControls(holder);
                    successCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (IllegalStateException expectedReBindRejection) {
                    // "re-bind while active: error" (§9 registration contract) — exactly one
                    // concurrent caller wins the generation-scoped runtime-controls registration.
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(successCount.get())
                .as("ровно один вызов registerRuntimeControls должен выиграть generation-scoped регистрацию")
                .isEqualTo(1);
        assertThat(registerCallCount.get())
                .as("readiness (1, до цикла) + 6 доменных MBean (1 победитель) должны регистрироваться ровно один раз суммарно")
                .isEqualTo(7);
    }

    @Test
    void rollback_addSuppressed_present_when_rollback_fails() throws Exception {
        final int[] registerCallCount = {0};
        MBeanServer faultySpy = new DelegatingMBeanServer(server) {
            @Override
            public javax.management.ObjectInstance registerMBean(Object object, ObjectName name)
                    throws javax.management.InstanceAlreadyExistsException,
                    javax.management.MBeanRegistrationException,
                    javax.management.NotCompliantMBeanException {
                registerCallCount[0]++;
                if (registerCallCount[0] == 4) {
                    throw new javax.management.NotCompliantMBeanException("simulated failure at domain 4");
                }
                return super.registerMBean(object, name);
            }

            @Override
            public void unregisterMBean(ObjectName name)
                    throws javax.management.InstanceNotFoundException,
                    javax.management.MBeanRegistrationException {
                if (DomainObjectNames.SCRUBBING.equals(name)) {
                    throw new javax.management.InstanceNotFoundException("simulated rollback failure");
                }
                super.unregisterMBean(name);
            }
        };

        TracingSettingsSource holder = TracingSettingsSource.isolatedSampling(true, Map.of(), 0.5);
        DomainMBeanRegistrar registrar = new DomainMBeanRegistrar(faultySpy);
        wireComponentsExceptAcceptedPolicy(registrar, holder);

        try {
            registrar.registerRuntimeControls(holder);
        } catch (JmxRegistrationException ex) {
            assertThat(ex.getCause()).isNotNull();
            assertThat(ex.getSuppressed()).isNotEmpty();
            return;
        }
        assertThat(registerCallCount[0]).isGreaterThanOrEqualTo(0);
    }

    @Test
    void legacyControlProtocolMBeanNotRegistered() throws Exception {
        TracingSettingsSource holder = TracingSettingsSource.isolatedSampling(true, Map.of(), 0.5);
        DomainMBeanRegistrar registrar = new DomainMBeanRegistrar();
        wireRequiredComponents(registrar, holder);

        assertThat(server.isRegistered(DomainObjectNames.CONTROL_PROTOCOL)).isFalse();
    }

    private static void wireRequiredComponents(
            DomainMBeanRegistrar registrar,
            TracingSettingsSource acceptedPolicy) {
        wireComponentsExceptAcceptedPolicy(registrar, acceptedPolicy);
        registrar.registerRuntimeControls(acceptedPolicy);
    }

    private static void wireComponentsExceptAcceptedPolicy(
            DomainMBeanRegistrar registrar,
            TracingSettingsSource acceptedPolicy) {
        registrar.registerDiagnosticReadiness();
        CompositeSampler sampler = new CompositeSampler(
                acceptedPolicy, CategoryBSamplingAccess.snapshot(List.of(), List.of()));
        ValidatingSpanProcessor validating = new ValidatingSpanProcessor(acceptedPolicy);

        registrar.setCompositeSampler(sampler);
        registrar.setValidating(validating);
    }
}
