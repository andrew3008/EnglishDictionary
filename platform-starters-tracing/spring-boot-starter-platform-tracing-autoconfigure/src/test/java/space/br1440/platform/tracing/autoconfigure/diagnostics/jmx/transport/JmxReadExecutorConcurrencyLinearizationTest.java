package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.management.ManagementFactory;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.DynamicMBean;
import javax.management.InvalidAttributeValueException;
import javax.management.MBeanInfo;
import javax.management.MBeanNotificationInfo;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.RuntimeMBeanException;
import javax.management.openmbean.OpenMBeanAttributeInfo;
import javax.management.openmbean.OpenMBeanAttributeInfoSupport;
import javax.management.openmbean.OpenMBeanConstructorInfo;
import javax.management.openmbean.OpenMBeanInfoSupport;
import javax.management.openmbean.OpenMBeanOperationInfo;
import javax.management.openmbean.SimpleType;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

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
        JmxAttribute<Long> exportAttr = longAttribute(JmxContractPoint.EXPORT_OBSERVATION);
        JmxAttribute<Long> samplingAttr = longAttribute(JmxContractPoint.SAMPLING_OBSERVATION);

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
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
    }

    @Test
    void decodeRunsOutsideTransportLockSoOtherDomainProceedsDuringSlowDecode() throws Exception {
        CountDownLatch decodeEntered = new CountDownLatch(1);
        CountDownLatch releaseDecode = new CountDownLatch(1);
        AtomicBoolean otherDomainFinishedDuringDecode = new AtomicBoolean();

        registerFakeMBean(EXPORT_NAME, name -> 10L);
        registerFakeMBean(SAMPLING_NAME, name -> 20L);

        JmxReadExecutor executor = new JmxReadExecutor(server);
        JmxAttribute<Long> slowDecode = new JmxAttribute<>(
                "Value",
                JmxContractPoint.EXPORT_OBSERVATION,
                SimpleType.LONG,
                raw -> {
                    decodeEntered.countDown();
                    await(releaseDecode);
                    return (Long) raw;
                });
        JmxAttribute<Long> fastOther = longAttribute(JmxContractPoint.SAMPLING_OBSERVATION);

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
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
    }

    @Test
    void concurrentPresentAndExecutionFailureOrderedByObservationSequence() throws Exception {
        CountDownLatch bothReady = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger transportInvocations = new AtomicInteger();

        registerFakeMBean(EXPORT_NAME, name -> {
            int invocation = transportInvocations.incrementAndGet();
            if (invocation == 1) {
                return 42L;
            }
            throw new RuntimeMBeanException(new IllegalStateException("boom"));
        });

        JmxReadExecutor executor = new JmxReadExecutor(server);
        JmxAttribute<Long> attribute = longAttribute(JmxContractPoint.EXPORT_OBSERVATION);

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
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
            assertThat(first.isPresent() ^ second.isPresent()).isTrue();
            assertThat(transportInvocations.get()).isEqualTo(2);

            assertThat(executor.lastSuccessAge(JmxContractPoint.EXPORT_OBSERVATION)).isPresent();
            assertThat(executor.diagnosticSnapshot()).isNotEmpty();
        }
    }

    private static JmxAttribute<Long> longAttribute(JmxContractPoint point) {
        return new JmxAttribute<>("Value", point, SimpleType.LONG, raw -> {
            if (raw instanceof Long value) {
                return value;
            }

            throw new JmxContractViolationException(JmxDiagnosticKind.VALUE_TYPE_MISMATCH);
        });
    }

    private void registerFakeMBean(ObjectName objectName, java.util.function.Function<String, Object> supplier) throws Exception {
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
                    "space.br1440.platform.tracing.test:type=JmxReadExecutorConcurrencyLinearizationTest,name=" + suffix
            );
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
        public Object getAttribute(String attribute) {
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
            OpenMBeanAttributeInfo attribute = new OpenMBeanAttributeInfoSupport(
                    "Value",
                    "value",
                    SimpleType.LONG,
                    true,
                    false,
                    false
            );

            return new OpenMBeanInfoSupport(
                    getClass().getName(),
                    "fake",
                    new OpenMBeanAttributeInfo[]{attribute},
                    new OpenMBeanConstructorInfo[0],
                    new OpenMBeanOperationInfo[0],
                    new MBeanNotificationInfo[0]
            );
        }
    }
}
