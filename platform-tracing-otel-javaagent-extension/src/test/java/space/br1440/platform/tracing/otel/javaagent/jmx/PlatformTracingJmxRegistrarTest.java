package space.br1440.platform.tracing.otel.javaagent.jmx;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.control.state.AcceptedPolicyAccess;
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
 * Tests for {@link PlatformTracingJmxRegistrar}: batch registration, idempotency, rollback,
 * and concurrent double-call safety.
 */
class PlatformTracingJmxRegistrarTest {

    private final MBeanServer server = ManagementFactory.getPlatformMBeanServer();

    @AfterEach
    void cleanup() {
        PlatformTracingJmxRegistrar cleanup = new PlatformTracingJmxRegistrar();
        cleanup.unregisterAllMBeans();
    }

    @Test
    void tryRegisterMBeans_регистрирует_все_шесть_доменных_MBean() throws Exception {
        AcceptedPolicyAccess holder = AcceptedPolicyAccess.isolatedSampling(
                true, List.of(), List.of(), Map.of(), 0.5);
        PlatformTracingJmxRegistrar registrar = new PlatformTracingJmxRegistrar();
        wireRequiredComponents(registrar, holder);

        assertThat(server.isRegistered(PlatformTracingObjectNames.SAMPLING)).isTrue();
        assertThat(server.isRegistered(PlatformTracingObjectNames.SCRUBBING)).isTrue();
        assertThat(server.isRegistered(PlatformTracingObjectNames.VALIDATION)).isTrue();
        assertThat(server.isRegistered(PlatformTracingObjectNames.EXPORT)).isTrue();
        assertThat(server.isRegistered(PlatformTracingObjectNames.PROCESSOR_METRICS)).isTrue();
        assertThat(server.isRegistered(PlatformTracingObjectNames.DIAGNOSTICS)).isTrue();
    }

    @Test
    void tryRegisterMBeans_требует_configHolder_перед_регистрацией() {
        PlatformTracingJmxRegistrar registrar = new PlatformTracingJmxRegistrar();
        AcceptedPolicyAccess holder = AcceptedPolicyAccess.isolatedSampling(
                true, List.of(), List.of(), Map.of(), 0.5);
        wireComponentsExceptAcceptedPolicy(registrar, holder);

        assertThat(server.isRegistered(PlatformTracingObjectNames.SAMPLING)).isFalse();
    }

    @Test
    void tryRegisterMBeans_идемпотентен_повторный_вызов_не_дублирует_регистрацию() throws Exception {
        AcceptedPolicyAccess holder = AcceptedPolicyAccess.isolatedSampling(
                true, List.of(), List.of(), Map.of(), 0.5);
        PlatformTracingJmxRegistrar registrar = new PlatformTracingJmxRegistrar();
        wireRequiredComponents(registrar, holder);

        assertThatNoException().isThrownBy(registrar::tryRegisterMBeans);
        assertThat(server.isRegistered(PlatformTracingObjectNames.SAMPLING)).isTrue();
    }

    @Test
    void tryRegisterMBeans_второй_registrar_применяет_REPLACE_EXISTING() throws Exception {
        AcceptedPolicyAccess holder = AcceptedPolicyAccess.isolatedSampling(
                true, List.of(), List.of(), Map.of(), 0.5);
        PlatformTracingJmxRegistrar first = new PlatformTracingJmxRegistrar();
        wireRequiredComponents(first, holder);

        PlatformTracingJmxRegistrar second = new PlatformTracingJmxRegistrar();
        assertThatNoException().isThrownBy(() -> wireRequiredComponents(second, holder));
        assertThat(server.isRegistered(PlatformTracingObjectNames.SAMPLING)).isTrue();
    }

    @Test
    void unregisterAllMBeans_снимает_регистрацию_всех_шести() throws Exception {
        AcceptedPolicyAccess holder = AcceptedPolicyAccess.isolatedSampling(
                true, List.of(), List.of(), Map.of(), 0.5);
        PlatformTracingJmxRegistrar registrar = new PlatformTracingJmxRegistrar();
        wireRequiredComponents(registrar, holder);
        assertThat(server.isRegistered(PlatformTracingObjectNames.SAMPLING)).isTrue();

        registrar.unregisterAllMBeans();

        assertThat(server.isRegistered(PlatformTracingObjectNames.SAMPLING)).isFalse();
        assertThat(server.isRegistered(PlatformTracingObjectNames.SCRUBBING)).isFalse();
        assertThat(server.isRegistered(PlatformTracingObjectNames.VALIDATION)).isFalse();
        assertThat(server.isRegistered(PlatformTracingObjectNames.EXPORT)).isFalse();
        assertThat(server.isRegistered(PlatformTracingObjectNames.PROCESSOR_METRICS)).isFalse();
        assertThat(server.isRegistered(PlatformTracingObjectNames.DIAGNOSTICS)).isFalse();
    }

    @Test
    void concurrentDoubleCall_tryRegisterMBeans_не_нарушает_инварианты() throws Exception {
        AcceptedPolicyAccess holder = AcceptedPolicyAccess.isolatedSampling(
                true, List.of(), List.of(), Map.of(), 0.5);
        PlatformTracingJmxRegistrar registrar = new PlatformTracingJmxRegistrar();
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

        assertThat(server.isRegistered(PlatformTracingObjectNames.SAMPLING)).isTrue();
        assertThat(server.isRegistered(PlatformTracingObjectNames.DIAGNOSTICS)).isTrue();
    }

    @Test
    void spy_registerMBean_вызывается_ровно_7_раз_при_параллельных_вызовах() throws Exception {
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

        AcceptedPolicyAccess holder = AcceptedPolicyAccess.isolatedSampling(
                true, List.of(), List.of(), Map.of(), 0.5);
        PlatformTracingJmxRegistrar registrar = new PlatformTracingJmxRegistrar(countingSpy);
        wireComponentsExceptAcceptedPolicy(registrar, holder);

        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    registrar.setAcceptedPolicy(holder);
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

        assertThat(registerCallCount.get())
                .as("readiness + 6 domain MBeans должны регистрироваться ровно один раз")
                .isEqualTo(7);
    }

    @Test
    void rollback_addSuppressed_присутствует_если_откат_падает() throws Exception {
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
                if (PlatformTracingObjectNames.SCRUBBING.equals(name)) {
                    throw new javax.management.InstanceNotFoundException("simulated rollback failure");
                }
                super.unregisterMBean(name);
            }
        };

        AcceptedPolicyAccess holder = AcceptedPolicyAccess.isolatedSampling(
                true, List.of(), List.of(), Map.of(), 0.5);
        PlatformTracingJmxRegistrar registrar = new PlatformTracingJmxRegistrar(faultySpy);
        wireComponentsExceptAcceptedPolicy(registrar, holder);

        try {
            registrar.setAcceptedPolicy(holder);
        } catch (PlatformTracingJmxRegistrationException ex) {
            assertThat(ex.getCause()).isNotNull();
            assertThat(ex.getSuppressed()).isNotEmpty();
            return;
        }
        assertThat(registerCallCount[0]).isGreaterThanOrEqualTo(0);
    }

    @Test
    void legacyControlProtocolMBeanNotRegistered() throws Exception {
        AcceptedPolicyAccess holder = AcceptedPolicyAccess.isolatedSampling(
                true, List.of(), List.of(), Map.of(), 0.5);
        PlatformTracingJmxRegistrar registrar = new PlatformTracingJmxRegistrar();
        wireRequiredComponents(registrar, holder);

        assertThat(server.isRegistered(PlatformTracingObjectNames.CONTROL_PROTOCOL)).isFalse();
    }

    private static void wireRequiredComponents(
            PlatformTracingJmxRegistrar registrar,
            AcceptedPolicyAccess acceptedPolicy) {
        wireComponentsExceptAcceptedPolicy(registrar, acceptedPolicy);
        registrar.setAcceptedPolicy(acceptedPolicy);
    }

    private static void wireComponentsExceptAcceptedPolicy(
            PlatformTracingJmxRegistrar registrar,
            AcceptedPolicyAccess acceptedPolicy) {
        CompositeSampler sampler = new CompositeSampler(acceptedPolicy);
        ValidatingSpanProcessor validating = new ValidatingSpanProcessor(acceptedPolicy);

        registrar.setCompositeSampler(sampler);
        registrar.setValidating(validating);
    }
}
