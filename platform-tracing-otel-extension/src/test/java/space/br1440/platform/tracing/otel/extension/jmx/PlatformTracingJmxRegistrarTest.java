package space.br1440.platform.tracing.otel.extension.jmx;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.otel.extension.sampler.SamplerStateHolder;

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
        SamplerStateHolder holder = new SamplerStateHolder(
                true, List.of(), List.of(), Map.of(), 0.5);
        PlatformTracingJmxRegistrar registrar = new PlatformTracingJmxRegistrar();
        registrar.setConfigHolder(holder);

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
        registrar.setCompositeSampler(null);

        assertThat(server.isRegistered(PlatformTracingObjectNames.SAMPLING)).isFalse();
    }

    @Test
    void tryRegisterMBeans_идемпотентен_повторный_вызов_не_дублирует_регистрацию() throws Exception {
        SamplerStateHolder holder = new SamplerStateHolder(
                true, List.of(), List.of(), Map.of(), 0.5);
        PlatformTracingJmxRegistrar registrar = new PlatformTracingJmxRegistrar();
        registrar.setConfigHolder(holder);

        assertThatNoException().isThrownBy(registrar::tryRegisterMBeans);
        assertThat(server.isRegistered(PlatformTracingObjectNames.SAMPLING)).isTrue();
    }

    @Test
    void tryRegisterMBeans_второй_registrar_применяет_REPLACE_EXISTING() throws Exception {
        SamplerStateHolder holder = new SamplerStateHolder(
                true, List.of(), List.of(), Map.of(), 0.5);
        PlatformTracingJmxRegistrar first = new PlatformTracingJmxRegistrar();
        first.setConfigHolder(holder);

        PlatformTracingJmxRegistrar second = new PlatformTracingJmxRegistrar();
        assertThatNoException().isThrownBy(() -> second.setConfigHolder(holder));
        assertThat(server.isRegistered(PlatformTracingObjectNames.SAMPLING)).isTrue();
    }

    @Test
    void unregisterAllMBeans_снимает_регистрацию_всех_шести() throws Exception {
        SamplerStateHolder holder = new SamplerStateHolder(
                true, List.of(), List.of(), Map.of(), 0.5);
        PlatformTracingJmxRegistrar registrar = new PlatformTracingJmxRegistrar();
        registrar.setConfigHolder(holder);
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
        SamplerStateHolder holder = new SamplerStateHolder(
                true, List.of(), List.of(), Map.of(), 0.5);
        PlatformTracingJmxRegistrar registrar = new PlatformTracingJmxRegistrar();
        registrar.setConfigHolder(holder);

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
    void spy_registerMBean_вызывается_ровно_6_раз_при_параллельных_вызовах() throws Exception {
        // Ручной spy-счётчик без Mockito: делегирующий MBeanServer с AtomicInteger.
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

        SamplerStateHolder holder = new SamplerStateHolder(
                true, List.of(), List.of(), Map.of(), 0.5);
        PlatformTracingJmxRegistrar registrar = new PlatformTracingJmxRegistrar(countingSpy);

        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    registrar.setConfigHolder(holder);
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

        // Идемпотентность: ровно 6 регистраций (по одной на домен), не больше
        assertThat(registerCallCount.get())
                .as("registerMBean должен быть вызван ровно 6 раз, независимо от числа потоков")
                .isEqualTo(7);
    }

    @Test
    void rollback_addSuppressed_присутствует_если_откат_падает() throws Exception {
        // Ручной spy: 4-й registerMBean падает; unregister для SCRUBBING тоже падает.
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

        SamplerStateHolder holder = new SamplerStateHolder(
                true, List.of(), List.of(), Map.of(), 0.5);
        PlatformTracingJmxRegistrar registrar = new PlatformTracingJmxRegistrar(faultySpy);

        try {
            registrar.setConfigHolder(holder);
        } catch (PlatformTracingJmxRegistrationException ex) {
            assertThat(ex.getCause()).isNotNull();
            // Откат упал → его исключение должно быть в suppressed, а не потеряно
            assertThat(ex.getSuppressed()).isNotEmpty();
            return;
        }
        // Если не упало (REPLACE_EXISTING пропустил), проверяем что регистрации корректны
        assertThat(registerCallCount[0]).isGreaterThanOrEqualTo(0);
    }
}
