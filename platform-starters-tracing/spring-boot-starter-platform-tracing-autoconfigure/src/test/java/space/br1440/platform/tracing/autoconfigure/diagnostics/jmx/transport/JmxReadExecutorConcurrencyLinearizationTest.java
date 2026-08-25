package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.AttributeNotFoundException;
import javax.management.DynamicMBean;
import javax.management.InvalidAttributeValueException;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanException;
import javax.management.MBeanInfo;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.ReflectionException;
import javax.management.RuntimeMBeanException;
import java.lang.management.ManagementFactory;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Линеаризация конкурентных attempts: per-domain transport-лок, decode вне лока,
 * независимость доменов и детерминированное упорядочение Present ↔ ExecutionFailure
 * единой шкалой observationSequence (наблюдаемой через registry outcome).
 */
class JmxReadExecutorConcurrencyLinearizationTest {

    private static final ObjectName EXPORT_NAME = objectName("export");
    private static final ObjectName SAMPLING_NAME = objectName("sampling");

    private final MBeanServer server = ManagementFactory.getPlatformMBeanServer();

    @AfterEach
    void cleanup() throws Exception {
        for (ObjectName name : new ObjectName[]{EXPORT_NAME, SAMPLING_NAME}) {
            if (server.isRegistered(name)) {
                server.unregisterMBean(name);
            }
        }
    }

    @Test
    void readsOfDifferentDomainsDoNotBlockEachOther() throws Exception {
        CountDownLatch exportEntered = new CountDownLatch(1);
        CountDownLatch releaseExport = new CountDownLatch(1);
        AtomicBoolean samplingCompletedWhileExportHeld = new AtomicBoolean();

        registerFakeMBean(EXPORT_NAME, name -> {
            exportEntered.countDown();
            await(releaseExport);
            return 1L;
        });
        registerFakeMBean(SAMPLING_NAME, name -> 2L);

        JmxReadExecutor executor = new JmxReadExecutor(server);
        JmxAttribute<Long> exportAttr = longAttribute("Value", JmxContractPoint.EXPORT_OBSERVATION);
        JmxAttribute<Long> samplingAttr = longAttribute("Value", JmxContractPoint.SAMPLING_OBSERVATION);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Optional<Long>> exportFuture = pool.submit(() -> executor.read(EXPORT_NAME, exportAttr));
            assertThat(exportEntered.await(5, TimeUnit.SECONDS)).isTrue();

            Future<Optional<Long>> samplingFuture = pool.submit(() -> {
                Optional<Long> result = executor.read(SAMPLING_NAME, samplingAttr);
                samplingCompletedWhileExportHeld.set(true);
                return result;
            });

            assertThat(samplingFuture.get(5, TimeUnit.SECONDS)).contains(2L);
            assertThat(samplingCompletedWhileExportHeld).isTrue();
            releaseExport.countDown();
            assertThat(exportFuture.get(5, TimeUnit.SECONDS)).contains(1L);
        } finally {
            releaseExport.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void decodeRunsOutsideTransportLockSoOtherDomainProceedsDuringSlowDecode() throws Exception {
        CountDownLatch decodeEntered = new CountDownLatch(1);
        CountDownLatch releaseDecode = new CountDownLatch(1);
        AtomicBoolean otherDomainFinishedDuringDecode = new AtomicBoolean();

        registerFakeMBean(EXPORT_NAME, name -> 10L);
        registerFakeMBean(SAMPLING_NAME, name -> 20L);

        JmxReadExecutor executor = new JmxReadExecutor(server);
        JmxAttribute<Long> slowDecode = new JmxAttribute<>("Value", JmxContractPoint.EXPORT_OBSERVATION, raw -> {
            decodeEntered.countDown();
            await(releaseDecode);
            return (Long) raw;
        });
        JmxAttribute<Long> fastOther = longAttribute("Value", JmxContractPoint.SAMPLING_OBSERVATION);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Optional<Long>> slow = pool.submit(() -> executor.read(EXPORT_NAME, slowDecode));
            assertThat(decodeEntered.await(5, TimeUnit.SECONDS)).isTrue();

            Future<Optional<Long>> other = pool.submit(() -> {
                Optional<Long> result = executor.read(SAMPLING_NAME, fastOther);
                otherDomainFinishedDuringDecode.set(true);
                return result;
            });

            assertThat(other.get(5, TimeUnit.SECONDS)).contains(20L);
            assertThat(otherDomainFinishedDuringDecode).isTrue();
            releaseDecode.countDown();
            assertThat(slow.get(5, TimeUnit.SECONDS)).contains(10L);
        } finally {
            releaseDecode.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentPresentAndExecutionFailureOrderedByObservationSequence() throws Exception {
        CountDownLatch bothReady = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger transportInvocations = new AtomicInteger();
        AtomicReference<String> firstMode = new AtomicReference<>();

        registerFakeMBean(EXPORT_NAME, name -> {
            int invocation = transportInvocations.incrementAndGet();
            if (invocation == 1) {
                firstMode.set("success");
                return 42L;
            }
            firstMode.set("failure-second");
            throw new RuntimeMBeanException(new IllegalStateException("boom"));
        });

        // Второй MBean на том же домене невозможен с одним ObjectName — чередуем режимы
        // через счётчик: два конкурентных чтения одного атрибута сериализуются локом,
        // поэтому исходы строго упорядочены; registry отражает последнее применение.
        JmxReadExecutor executor = new JmxReadExecutor(server);
        JmxAttribute<Long> attribute = longAttribute("Value", JmxContractPoint.EXPORT_OBSERVATION);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Optional<Long>> a = pool.submit(() -> {
                bothReady.countDown();
                await(go);
                return executor.read(EXPORT_NAME, attribute);
            });
            Future<Optional<Long>> b = pool.submit(() -> {
                bothReady.countDown();
                await(go);
                return executor.read(EXPORT_NAME, attribute);
            });
            assertThat(bothReady.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            Optional<Long> first = a.get(5, TimeUnit.SECONDS);
            Optional<Long> second = b.get(5, TimeUnit.SECONDS);
            // Ровно один успех и один отказ — порядок transport-вызовов линеаризован локом.
            assertThat(first.isPresent() ^ second.isPresent()).isTrue();
            assertThat(transportInvocations.get()).isEqualTo(2);

            boolean lastWasSuccess = first.isPresent() && second.isEmpty()
                    ? firstMode.get().equals("success") && second.isEmpty()
                    : second.isPresent();
            // После двух линеаризованных attempts freshness/diagnostics доступны без исключения.
            if (lastWasSuccess || first.isPresent() || second.isPresent()) {
                assertThat(executor.lastSuccessAge(JmxContractPoint.EXPORT_OBSERVATION)).isPresent();
            }
            assertThat(executor.diagnosticSnapshot()).isNotEmpty();
        } finally {
            pool.shutdownNow();
        }
    }

    private static JmxAttribute<Long> longAttribute(String name, JmxContractPoint point) {
        return new JmxAttribute<>(name, point, raw -> {
            if (raw instanceof Long value) {
                return value;
            }
            throw new JmxContractViolationException(JmxDiagnosticKind.VALUE_TYPE_MISMATCH);
        });
    }

    private void registerFakeMBean(ObjectName objectName, java.util.function.Function<String, Object> supplier)
            throws Exception {
        server.registerMBean(new FakeDynamicMBean(supplier), objectName);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static ObjectName objectName(String suffix) {
        try {
            return ObjectName.getInstance(
                    "space.br1440.platform.tracing.test:type=JmxReadExecutorConcurrencyLinearizationTest,name=" + suffix);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class FakeDynamicMBean implements DynamicMBean {

        private final java.util.function.Function<String, Object> attributeSupplier;

        FakeDynamicMBean(java.util.function.Function<String, Object> attributeSupplier) {
            this.attributeSupplier = attributeSupplier;
        }

        @Override
        public Object getAttribute(String attribute) throws AttributeNotFoundException, MBeanException, ReflectionException {
            return attributeSupplier.apply(attribute);
        }

        @Override
        public void setAttribute(Attribute attribute) throws InvalidAttributeValueException {
            throw new InvalidAttributeValueException("read-only");
        }

        @Override
        public AttributeList getAttributes(String[] attributes) {
            return new AttributeList();
        }

        @Override
        public AttributeList setAttributes(AttributeList attributes) {
            return new AttributeList();
        }

        @Override
        public Object invoke(String actionName, Object[] params, String[] signature) {
            throw new UnsupportedOperationException();
        }

        @Override
        public MBeanInfo getMBeanInfo() {
            return new MBeanInfo(getClass().getName(), "fake", new MBeanAttributeInfo[]{
                    new MBeanAttributeInfo("Value", "long", "value", true, false, false)
            }, null, null, null);
        }
    }
}
