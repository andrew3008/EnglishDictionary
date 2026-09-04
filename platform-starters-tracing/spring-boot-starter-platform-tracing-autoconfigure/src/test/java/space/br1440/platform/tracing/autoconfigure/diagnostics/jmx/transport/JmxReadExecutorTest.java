package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.AttributeNotFoundException;
import javax.management.DynamicMBean;
import javax.management.InvalidAttributeValueException;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanException;
import javax.management.MBeanInfo;
import javax.management.MBeanNotificationInfo;
import javax.management.MBeanServer;
import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.ReflectionException;
import javax.management.RuntimeErrorException;
import javax.management.RuntimeMBeanException;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenMBeanAttributeInfo;
import javax.management.openmbean.OpenMBeanAttributeInfoSupport;
import javax.management.openmbean.OpenMBeanConstructorInfo;
import javax.management.openmbean.OpenMBeanConstructorInfoSupport;
import javax.management.openmbean.OpenMBeanInfoSupport;
import javax.management.openmbean.OpenMBeanOperationInfo;
import javax.management.openmbean.OpenMBeanParameterInfo;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxOpenTypes;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;

/**
 * Покрывает адверсариально-проверенные инварианты {@link JmxReadExecutor}: единую шкалу
 * {@code observationSequence} для всех четырёх исходов, сериализацию transport-вызова
 * per domain, классификацию исключений без broad catch и WARN-политику registry.
 */
final class JmxReadExecutorTest {

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
        assertThat(executor.diagnosticSnapshot()).isEmpty();
    }

    @Test
    void missingAttributeIsClassifiedAsContractViolation() throws Exception {
        registerFakeMBean("Missing", name -> {
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
    void customWireTypeIsRejectedBeforeDecoder() throws Exception {
        // given
        registerFakeMBean(name -> new ForeignDto());
        JmxReadExecutor executor = new JmxReadExecutor(server);
        AtomicBoolean decoderCalled = new AtomicBoolean();
        JmxAttribute<Object> attribute = identityAttribute(decoderCalled);

        // when
        Optional<Object> result = executor.read(TEST_OBJECT_NAME, attribute);

        // then
        assertThat(result).isEmpty();
        assertThat(decoderCalled).isFalse();
        assertWireTypeViolation(executor);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("prohibitedWireValues")
    void prohibitedWireValueIsRejectedBeforeDecoder(String scenario, Object value) throws Exception {
        // given
        registerFakeMBean(name -> value);
        JmxReadExecutor executor = new JmxReadExecutor(server);
        AtomicBoolean decoderCalled = new AtomicBoolean();
        JmxAttribute<Object> attribute = identityAttribute(decoderCalled);

        // when
        Optional<Object> result = executor.read(TEST_OBJECT_NAME, attribute);

        // then
        assertThat(result).isEmpty();
        assertThat(decoderCalled).isFalse();
        assertWireTypeViolation(executor);
    }

    @Test
    void emptyCustomArrayIsRejectedBeforeDecoder() throws Exception {
        // given
        registerFakeMBean(name -> new ForeignDto[0]);
        JmxReadExecutor executor = new JmxReadExecutor(server);
        AtomicBoolean decoderCalled = new AtomicBoolean();
        JmxAttribute<Object> attribute = identityAttribute(decoderCalled);

        // when
        Optional<Object> result = executor.read(TEST_OBJECT_NAME, attribute);

        // then
        assertThat(result).isEmpty();
        assertThat(decoderCalled).isFalse();
        assertWireTypeViolation(executor);
    }

    @Test
    void foreignCompositeDataIsRejectedBeforeDecoder() throws Exception {
        // given
        registerFakeMBean(name -> new ForeignCompositeData());
        JmxReadExecutor executor = new JmxReadExecutor(server);
        AtomicBoolean decoderCalled = new AtomicBoolean();
        JmxAttribute<Object> attribute = identityAttribute(decoderCalled);

        // when
        Optional<Object> result = executor.read(TEST_OBJECT_NAME, attribute);

        // then
        assertThat(result).isEmpty();
        assertThat(decoderCalled).isFalse();
        assertWireTypeViolation(executor);
    }

    @Test
    void stringBeyondGeneratedUtf8LimitIsRejectedBeforeDecoder() throws Exception {
        // given
        registerFakeMBean(name -> "x".repeat(JmxWireContract.MAX_STRING_UTF8_BYTES + 1));
        JmxReadExecutor executor = new JmxReadExecutor(server);
        AtomicBoolean decoderCalled = new AtomicBoolean();
        JmxAttribute<Object> attribute = identityAttribute(decoderCalled);

        // when
        Optional<Object> result = executor.read(TEST_OBJECT_NAME, attribute);

        // then
        assertThat(result).isEmpty();
        assertThat(decoderCalled).isFalse();
        assertWireTypeViolation(executor);
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
        // given
        registerFakeMBean(name -> 42L);
        JmxReadExecutor executor = new JmxReadExecutor(server);
        JmxAttribute<Long> attribute = longAttribute("Value", JmxContractPoint.EXPORT_OBSERVATION);

        // when
        Optional<Long> result = executor.read(TEST_OBJECT_NAME, attribute);

        // then
        assertThat(result).contains(42L);
        assertThat(executor.diagnosticSnapshot()).isEmpty();
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
        assertThat(executor.diagnosticSnapshot())
                .singleElement()
                .satisfies(snapshot -> assertThat(snapshot.kind())
                        .isEqualTo(JmxDiagnosticKind.MBEAN_EXECUTION_FAILURE));
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
            throw new IllegalStateException("Unexpected defect inside MBean");
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
        JmxAttribute<Long> attribute = new JmxAttribute<>(
                "Value",
                JmxContractPoint.EXPORT_OBSERVATION,
                SimpleType.LONG,
                raw -> {
                    throw new IllegalStateException("Descriptor defect is not a JMX failure");
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

        try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
            List<Future<Optional<Long>>> futures = List.of(
                    pool.submit(() -> executor.read(TEST_OBJECT_NAME, attribute)),
                    pool.submit(() -> executor.read(TEST_OBJECT_NAME, attribute)),
                    pool.submit(() -> executor.read(TEST_OBJECT_NAME, attribute)),
                    pool.submit(() -> executor.read(TEST_OBJECT_NAME, attribute)));
            for (Future<Optional<Long>> future : futures) {
                assertThat(future.get(5, TimeUnit.SECONDS)).contains(1L);
            }
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

    @Test
    void wrongMetadataIsRejectedBeforeAttributeRead() throws Exception {
        // given
        AtomicBoolean attributeRead = new AtomicBoolean();
        registerFakeMBean(name -> {
            attributeRead.set(true);
            return 42L;
        }, false);
        JmxReadExecutor executor = new JmxReadExecutor(server);
        JmxAttribute<Long> attribute = longAttribute("Value", JmxContractPoint.EXPORT_OBSERVATION);

        // when
        Optional<Long> result = executor.read(TEST_OBJECT_NAME, attribute);

        // then
        assertThat(result).isEmpty();
        assertThat(attributeRead).isFalse();
        assertShapeViolation(executor);
    }

    @Test
    void differentCompositeOpenTypeWithSameJavaClassNameIsRejectedBeforeAttributeRead() throws Exception {
        // given
        AtomicBoolean attributeRead = new AtomicBoolean();
        registerShapedMBean(
                snapshotInfo(JmxOpenTypes.RUNTIME_POLICY),
                name -> {
                    attributeRead.set(true);
                    return 42L;
                });
        JmxReadExecutor executor = new JmxReadExecutor(server);
        JmxAttribute<Object> attribute = new JmxAttribute<>(
                "Value",
                JmxContractPoint.EXPORT_OBSERVATION,
                JmxOpenTypes.READINESS_SNAPSHOT,
                raw -> raw);

        // when
        Optional<Object> result = executor.read(TEST_OBJECT_NAME, attribute);

        // then
        assertThat(result).isEmpty();
        assertThat(attributeRead).isFalse();
        assertShapeViolation(executor);
    }

    @Test
    void declaredConstructorIsRejectedBeforeAttributeRead() throws Exception {
        // given
        OpenMBeanConstructorInfo constructor = new OpenMBeanConstructorInfoSupport(
                "fixture",
                "fixture",
                new OpenMBeanParameterInfo[0]);
        AtomicBoolean attributeRead = new AtomicBoolean();
        registerShapedMBean(
                snapshotInfo(
                        SimpleType.LONG,
                        new OpenMBeanConstructorInfo[]{constructor},
                        new MBeanNotificationInfo[0]),
                name -> {
                    attributeRead.set(true);
                    return 42L;
                });
        JmxReadExecutor executor = new JmxReadExecutor(server);

        // when
        Optional<Long> result = executor.read(
                TEST_OBJECT_NAME,
                longAttribute("Value", JmxContractPoint.EXPORT_OBSERVATION));

        // then
        assertThat(result).isEmpty();
        assertThat(attributeRead).isFalse();
        assertShapeViolation(executor);
    }

    @Test
    void declaredNotificationIsRejectedBeforeAttributeRead() throws Exception {
        // given
        MBeanNotificationInfo notification = new MBeanNotificationInfo(
                new String[]{"fixture"},
                "fixture",
                "fixture");
        AtomicBoolean attributeRead = new AtomicBoolean();
        registerShapedMBean(
                snapshotInfo(
                        SimpleType.LONG,
                        new OpenMBeanConstructorInfo[0],
                        new MBeanNotificationInfo[]{notification}),
                name -> {
                    attributeRead.set(true);
                    return 42L;
                });
        JmxReadExecutor executor = new JmxReadExecutor(server);

        // when
        Optional<Long> result = executor.read(
                TEST_OBJECT_NAME,
                longAttribute("Value", JmxContractPoint.EXPORT_OBSERVATION));

        // then
        assertThat(result).isEmpty();
        assertThat(attributeRead).isFalse();
        assertShapeViolation(executor);
    }

    @Test
    void nullMetadataIsRejectedAsShapeViolationWithoutProviderFailure() throws Exception {
        // given
        MBeanServerConnection connection = mock(MBeanServerConnection.class);
        when(connection.getMBeanInfo(TEST_OBJECT_NAME)).thenReturn(null);
        JmxReadExecutor executor = new JmxReadExecutor(connection);
        JmxAttribute<Long> attribute = longAttribute("Value", JmxContractPoint.EXPORT_OBSERVATION);

        // when
        Optional<Long> result = executor.read(TEST_OBJECT_NAME, attribute);

        // then
        assertThat(result).isEmpty();
        assertShapeViolation(executor);
    }

    private static void assertShapeViolation(JmxReadExecutor executor) {
        assertThat(executor.diagnosticSnapshot())
                .singleElement()
                .satisfies(snapshot -> assertThat(snapshot.kind())
                        .isEqualTo(JmxDiagnosticKind.MBEAN_INFO_SHAPE_VIOLATION));
    }

    private static void assertWireTypeViolation(JmxReadExecutor executor) {
        assertThat(executor.diagnosticSnapshot())
                .singleElement()
                .satisfies(snapshot -> {
                    assertThat(snapshot.category()).isEqualTo(JmxDiagnosticCategory.CONTRACT_VIOLATION);
                    assertThat(snapshot.kind()).isEqualTo(JmxDiagnosticKind.JMX_WIRE_TYPE_VIOLATION);
                    assertThat(snapshot.unrecovered()).isTrue();
                });
    }

    private static Stream<Arguments> prohibitedWireValues() {
        Runnable proxy = (Runnable) Proxy.newProxyInstance(
                JmxReadExecutorTest.class.getClassLoader(),
                new Class<?>[]{Runnable.class},
                (target, method, arguments) -> switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(target);
                    case "equals" -> target == arguments[0];
                    case "toString" -> "ProhibitedWireValueProxy";
                    default -> null;
                });
        Runnable lambda = () -> {
        };
        return Stream.of(
                Arguments.of("Class", String.class),
                Arguments.of("ClassLoader", new ClassLoader(null) {
                }),
                Arguments.of("Throwable", new IllegalStateException("foreign")),
                Arguments.of("proxy", proxy),
                Arguments.of("lambda", lambda));
    }

    private static JmxAttribute<Object> identityAttribute(AtomicBoolean decoderCalled) {
        return new JmxAttribute<>(
                "Value",
                JmxContractPoint.EXPORT_OBSERVATION,
                SimpleType.LONG,
                raw -> {
                    decoderCalled.set(true);
                    return raw;
                });
    }

    @SuppressWarnings("SameParameterValue")
    private static JmxAttribute<Long> longAttribute(String name, JmxContractPoint point) {
        return new JmxAttribute<>(name, point, SimpleType.LONG, raw -> {
            if (raw instanceof Long value) {
                return value;
            }
            throw new JmxContractViolationException(JmxDiagnosticKind.VALUE_TYPE_MISMATCH);
        });
    }

    private void registerFakeMBean(Function<String, Object> attributeSupplier) throws Exception {
        registerFakeMBean("Value", attributeSupplier);
    }

    private void registerFakeMBean(
            String attributeName,
            Function<String, Object> attributeSupplier) throws Exception {
        server.registerMBean(new FakeDynamicMBean(attributeName, attributeSupplier, true), TEST_OBJECT_NAME);
    }

    @SuppressWarnings("SameParameterValue")
    private void registerFakeMBean(
            Function<String, Object> attributeSupplier,
            boolean exactMetadata) throws Exception {
        server.registerMBean(new FakeDynamicMBean("Value", attributeSupplier, exactMetadata), TEST_OBJECT_NAME);
    }

    private void registerShapedMBean(
            MBeanInfo info,
            Function<String, Object> attributeSupplier) throws Exception {
        server.registerMBean(new FakeDynamicMBean("Value", attributeSupplier, info), TEST_OBJECT_NAME);
    }

    @SuppressWarnings("SameParameterValue")
    private static MBeanInfo snapshotInfo(OpenType<?> openType) {
        return snapshotInfo(openType, new OpenMBeanConstructorInfo[0], new MBeanNotificationInfo[0]);
    }

    private static MBeanInfo snapshotInfo(OpenType<?> openType,
                                          OpenMBeanConstructorInfo[] constructors,
                                          MBeanNotificationInfo[] notifications) {
        OpenMBeanAttributeInfo attribute = new OpenMBeanAttributeInfoSupport(
                "Value",
                "value",
                openType,
                true,
                false,
                false
        );

        return new OpenMBeanInfoSupport(
                FakeDynamicMBean.class.getName(),
                "fake",
                new OpenMBeanAttributeInfo[]{attribute},
                constructors,
                new OpenMBeanOperationInfo[0],
                notifications
        );
    }

    private static RuntimeException wrap(Exception checked) {
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

    private static final class FakeDynamicMBean implements DynamicMBean {

        private final String attributeName;
        private final Function<String, Object> attributeSupplier;
        private final boolean exactMetadata;
        private final MBeanInfo customInfo;

        FakeDynamicMBean(String attributeName,
                         Function<String, Object> attributeSupplier,
                         boolean exactMetadata) {
            this.attributeName = attributeName;
            this.attributeSupplier = attributeSupplier;
            this.exactMetadata = exactMetadata;
            this.customInfo = null;
        }

        FakeDynamicMBean(String attributeName,
                         Function<String, Object> attributeSupplier,
                         MBeanInfo customInfo) {
            this.attributeName = attributeName;
            this.attributeSupplier = attributeSupplier;
            this.exactMetadata = true;
            this.customInfo = customInfo;
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
            return new AttributeList();
        }

        @Override
        public Object invoke(String actionName, Object[] params, String[] signature) {
            throw new UnsupportedOperationException();
        }

        @Override
        public MBeanInfo getMBeanInfo() {
            if (customInfo != null) {
                return customInfo;
            }

            if (exactMetadata) {
                OpenMBeanAttributeInfo attribute = new OpenMBeanAttributeInfoSupport(
                        attributeName,
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
                        new MBeanNotificationInfo[0]);
            }
            return new MBeanInfo(getClass().getName(), "fake", new MBeanAttributeInfo[]{
                    new MBeanAttributeInfo(attributeName, "long", "value", true, false, false)
            }, null, null, null);
        }
    }

    private record ForeignDto() {
    }

    private static final class ForeignCompositeData implements CompositeData {

        @Override
        public CompositeType getCompositeType() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object get(String key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object[] getAll(String[] keys) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean containsKey(String key) {
            return false;
        }

        @Override
        public boolean containsValue(Object value) {
            return false;
        }

        @Override
        public Collection<?> values() {
            return List.of();
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
