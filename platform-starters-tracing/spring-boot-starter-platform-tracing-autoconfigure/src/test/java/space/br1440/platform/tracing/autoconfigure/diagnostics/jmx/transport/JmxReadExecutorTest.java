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
import javax.management.RuntimeErrorException;
import javax.management.RuntimeMBeanException;
import java.lang.management.ManagementFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Покрывает адверсариально-проверенные инварианты {@link JmxReadExecutor}: единую шкалу
 * {@code observationSequence} для всех четырёх исходов, сериализацию transport-вызова
 * per domain, классификацию исключений без broad catch и WARN-политику registry.
 */
class JmxReadExecutorTest {

    private static final ObjectName TEST_OBJECT_NAME = objectName();
    private final MBeanServer server = ManagementFactory.getPlatformMBeanServer();

    @AfterEach
    void cleanup() throws Exception {
        if (server.isRegistered(TEST_OBJECT_NAME)) {
            server.unregisterMBean(TEST_OBJECT_NAME);
        }
    }

    @Test
    void absentMBeanYieldsEmptyWithoutWarn() {
        JmxReadExecutor executor = new JmxReadExecutor(server);
        JmxAttribute<Long> attribute = longAttribute("Value", JmxContractPoint.EXPORT_OBSERVATION);

        Optional<Long> result = executor.read(TEST_OBJECT_NAME, attribute);

        assertThat(result).isEmpty();
    }

    @Test
    void missingAttributeIsClassifiedAsContractViolation() throws Exception {
        registerFakeMBean(name -> {
            throw wrap(new AttributeNotFoundException(name));
        });
        JmxReadExecutor executor = new JmxReadExecutor(server);
        JmxAttribute<Long> attribute = longAttribute("Missing", JmxContractPoint.EXPORT_OBSERVATION);

        Optional<Long> result = executor.read(TEST_OBJECT_NAME, attribute);

        assertThat(result).isEmpty();
    }

    @Test
    void wrongScalarTypeIsClassifiedAsContractViolation() throws Exception {
        registerFakeMBean(name -> "not-a-long");
        JmxReadExecutor executor = new JmxReadExecutor(server);
        JmxAttribute<Long> attribute = longAttribute("Value", JmxContractPoint.EXPORT_OBSERVATION);

        Optional<Long> result = executor.read(TEST_OBJECT_NAME, attribute);

        assertThat(result).isEmpty();
    }

    @Test
    void nullResultIsClassifiedAsContractViolationNotAbsent() throws Exception {
        registerFakeMBean(name -> null);
        JmxReadExecutor executor = new JmxReadExecutor(server);
        JmxAttribute<Long> attribute = longAttribute("Value", JmxContractPoint.EXPORT_OBSERVATION);

        Optional<Long> result = executor.read(TEST_OBJECT_NAME, attribute);

        assertThat(result).isEmpty();
    }

    @Test
    void presentValueDecodesSuccessfully() throws Exception {
        registerFakeMBean(name -> 42L);
        JmxReadExecutor executor = new JmxReadExecutor(server);
        JmxAttribute<Long> attribute = longAttribute("Value", JmxContractPoint.EXPORT_OBSERVATION);

        Optional<Long> result = executor.read(TEST_OBJECT_NAME, attribute);

        assertThat(result).contains(42L);
    }

    @Test
    void mBeanExceptionIsClassifiedAsExecutionFailure() throws Exception {
        registerFakeMBean(name -> {
            throw wrap(new MBeanException(new IllegalStateException("boom")));
        });
        JmxReadExecutor executor = new JmxReadExecutor(server);
        JmxAttribute<Long> attribute = longAttribute("Value", JmxContractPoint.EXPORT_OBSERVATION);

        Optional<Long> result = executor.read(TEST_OBJECT_NAME, attribute);

        assertThat(result).isEmpty();
    }

    @Test
    void runtimeMBeanExceptionIsClassifiedAsExecutionFailure() throws Exception {
        registerFakeMBean(name -> {
            throw new RuntimeMBeanException(new IllegalStateException("boom"));
        });
        JmxReadExecutor executor = new JmxReadExecutor(server);
        JmxAttribute<Long> attribute = longAttribute("Value", JmxContractPoint.EXPORT_OBSERVATION);

        Optional<Long> result = executor.read(TEST_OBJECT_NAME, attribute);

        assertThat(result).isEmpty();
    }

    @Test
    void runtimeErrorExceptionIsClassifiedAsExecutionFailure() throws Exception {
        registerFakeMBean(name -> {
            throw new RuntimeErrorException(new AssertionError("boom"));
        });
        JmxReadExecutor executor = new JmxReadExecutor(server);
        JmxAttribute<Long> attribute = longAttribute("Value", JmxContractPoint.EXPORT_OBSERVATION);

        Optional<Long> result = executor.read(TEST_OBJECT_NAME, attribute);

        assertThat(result).isEmpty();
    }

    @Test
    void reflectionExceptionWithUnexpectedCauseIsExecutionFailureNotPropagated() throws Exception {
        registerFakeMBean(name -> {
            throw wrap(new ReflectionException(new IllegalStateException("unexpected cause"), "boom"));
        });
        JmxReadExecutor executor = new JmxReadExecutor(server);
        JmxAttribute<Long> attribute = longAttribute("Value", JmxContractPoint.EXPORT_OBSERVATION);

        Optional<Long> result = executor.read(TEST_OBJECT_NAME, attribute);

        assertThat(result).isEmpty();
    }

    @Test
    void unclassifiedRuntimeExceptionFromMBeanIsWrappedByJmxRuntimeAndClassifiedAsExecutionFailure() throws Exception {
        // Стандартный MBeanServer сам оборачивает произвольный RuntimeException из DynamicMBean
        // в RuntimeMBeanException — это и есть штатный путь для непредвиденных сбоев реализации.
        registerFakeMBean(name -> {
            throw new IllegalStateException("непредвиденный дефект внутри MBean");
        });
        JmxReadExecutor executor = new JmxReadExecutor(server);
        JmxAttribute<Long> attribute = longAttribute("Value", JmxContractPoint.EXPORT_OBSERVATION);

        Optional<Long> result = executor.read(TEST_OBJECT_NAME, attribute);

        assertThat(result).isEmpty();
    }

    @Test
    void decoderProgrammingDefectPropagatesInsteadOfBeingSwallowed() throws Exception {
        registerFakeMBean(name -> 42L);
        JmxReadExecutor executor = new JmxReadExecutor(server);
        JmxAttribute<Long> attribute = new JmxAttribute<>("Value", JmxContractPoint.EXPORT_OBSERVATION, raw -> {
            throw new IllegalStateException("дефект дескриптора — не JMX-отказ");
        });

        assertThatThrownBy(() -> executor.read(TEST_OBJECT_NAME, attribute))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void concurrentReadsOfSameDomainAreSerializedByTransportLock() throws Exception {
        AtomicInteger concurrentInvocations = new AtomicInteger();
        AtomicInteger maxObservedConcurrency = new AtomicInteger();
        registerFakeMBean(name -> {
            int current = concurrentInvocations.incrementAndGet();
            maxObservedConcurrency.updateAndGet(previous -> Math.max(previous, current));
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                concurrentInvocations.decrementAndGet();
            }
            return 1L;
        });
        JmxReadExecutor executor = new JmxReadExecutor(server);
        JmxAttribute<Long> attribute = longAttribute("Value", JmxContractPoint.EXPORT_OBSERVATION);

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<Optional<Long>>> futures = List.of(
                    pool.submit(() -> executor.read(TEST_OBJECT_NAME, attribute)),
                    pool.submit(() -> executor.read(TEST_OBJECT_NAME, attribute)),
                    pool.submit(() -> executor.read(TEST_OBJECT_NAME, attribute)),
                    pool.submit(() -> executor.read(TEST_OBJECT_NAME, attribute)));
            for (Future<Optional<Long>> future : futures) {
                assertThat(future.get(5, TimeUnit.SECONDS)).contains(1L);
            }
        } finally {
            pool.shutdown();
        }

        assertThat(maxObservedConcurrency.get()).isEqualTo(1);
    }

    @Test
    void observationSequenceIsStrictlyMonotonicAcrossSuccessAndFailure() throws Exception {
        AtomicLong callCount = new AtomicLong();
        registerFakeMBean(name -> {
            long call = callCount.incrementAndGet();
            if (call % 2 == 0) {
                throw new RuntimeMBeanException(new IllegalStateException("intermittent"));
            }
            return call;
        });
        JmxReadExecutor executor = new JmxReadExecutor(server);
        JmxAttribute<Long> attribute = longAttribute("Value", JmxContractPoint.EXPORT_OBSERVATION);

        Optional<Long> first = executor.read(TEST_OBJECT_NAME, attribute);
        Optional<Long> second = executor.read(TEST_OBJECT_NAME, attribute);
        Optional<Long> third = executor.read(TEST_OBJECT_NAME, attribute);

        assertThat(first).contains(1L);
        assertThat(second).isEmpty();
        assertThat(third).contains(3L);
    }

    @Test
    void warnFiresOnceOnFirstFailureAndAgainOnlyAfterAcceptedRecovery() throws Exception {
        FixedClock clock = new FixedClock(Instant.parse("2026-01-01T00:00:00Z"));
        JmxReadExecutor executor = new JmxReadExecutor(server, clock, Duration.ofMillis(0));
        AtomicInteger mode = new AtomicInteger(0);
        registerFakeMBean(name -> {
            if (mode.get() == 0) {
                throw new RuntimeMBeanException(new IllegalStateException("failing"));
            }
            return 7L;
        });
        JmxAttribute<Long> attribute = longAttribute("Value", JmxContractPoint.EXPORT_OBSERVATION);

        Optional<Long> failure = executor.read(TEST_OBJECT_NAME, attribute);
        assertThat(failure).isEmpty();

        mode.set(1);
        clock.advance(Duration.ofSeconds(1));
        Optional<Long> success = executor.read(TEST_OBJECT_NAME, attribute);
        assertThat(success).contains(7L);

        mode.set(0);
        clock.advance(Duration.ofSeconds(1));
        Optional<Long> secondFailure = executor.read(TEST_OBJECT_NAME, attribute);
        assertThat(secondFailure).isEmpty();
    }

    // -- Вспомогательные типы ----------------------------------------------------------

    private static JmxAttribute<Long> longAttribute(String name, JmxContractPoint point) {
        return new JmxAttribute<>(name, point, raw -> {
            if (raw instanceof Long value) {
                return value;
            }
            throw new JmxContractViolationException(JmxDiagnosticKind.VALUE_TYPE_MISMATCH);
        });
    }

    private void registerFakeMBean(java.util.function.Function<String, Object> attributeSupplier) throws Exception {
        server.registerMBean(new FakeDynamicMBean(attributeSupplier), TEST_OBJECT_NAME);
    }

    private static RuntimeException wrap(Exception checked) {
        // JUnit lambda не может объявлять checked-исключения; DynamicMBean#getAttribute сам
        // объявляет MBeanException/ReflectionException — оборачивание нужно только для теста.
        if (checked instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return new TestCheckedExceptionCarrier(checked);
    }

    private static ObjectName objectName() {
        try {
            return ObjectName.getInstance("space.br1440.platform.tracing.test:type=JmxReadExecutorTest");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Простой {@code DynamicMBean} с единственным настраиваемым атрибутом {@code Value/Missing}. */
    private static final class FakeDynamicMBean implements DynamicMBean {

        private final java.util.function.Function<String, Object> attributeSupplier;

        FakeDynamicMBean(java.util.function.Function<String, Object> attributeSupplier) {
            this.attributeSupplier = attributeSupplier;
        }

        @Override
        public Object getAttribute(String attribute) throws AttributeNotFoundException, MBeanException, ReflectionException {
            try {
                return attributeSupplier.apply(attribute);
            } catch (TestCheckedExceptionCarrier carrier) {
                Exception cause = carrier.checkedCause();
                if (cause instanceof MBeanException mBeanException) {
                    throw mBeanException;
                }
                if (cause instanceof ReflectionException reflectionException) {
                    throw reflectionException;
                }
                if (cause instanceof AttributeNotFoundException attributeNotFoundException) {
                    throw attributeNotFoundException;
                }
                throw new IllegalStateException(cause);
            }
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
            return new AttributeList(); // read-only тестовый MBean
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

    private static final class TestCheckedExceptionCarrier extends RuntimeException {
        private final Exception checkedCause;

        TestCheckedExceptionCarrier(Exception checkedCause) {
            super(checkedCause);
            this.checkedCause = checkedCause;
        }

        Exception checkedCause() {
            return checkedCause;
        }
    }

    private static final class FixedClock extends Clock {
        private Instant instant;

        FixedClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
