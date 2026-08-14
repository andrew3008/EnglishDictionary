package space.br1440.platform.tracing.autoconfigure.control.jmx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.DynamicMBean;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanInfo;
import javax.management.MBeanOperationInfo;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.ObjectName;
import javax.management.ReflectionException;
import javax.management.RuntimeErrorException;
import javax.management.RuntimeMBeanException;

import org.junit.jupiter.api.Test;

class PrivateControlLocatorTest {

    @Test
    void locatesExactlyOneCompatiblePrivateControlPlane() throws Exception {
        MBeanServer server = createCompatibleServer("runtime-a");
        try {
            PrivateControlLocator.LocatedPrivateControl located =
                    new PrivateControlLocator(() -> List.of(server), new PrivateControlMarkerInspector()).locate();

            assertThat(located.server()).isSameAs(server);
            assertThat(located.writerObjectName().getCanonicalName())
                    .isEqualTo(new ObjectName(PrivateControlLocator.WRITER_NAME).getCanonicalName());
            assertThat(located.runtimeInstanceId()).isEqualTo("runtime-a");
        } finally {
            MBeanServerFactory.releaseMBeanServer(server);
        }
    }

    @Test
    void rejectsMissingCompatiblePrivateControlPlane() {
        PrivateControlLocator locator =
                new PrivateControlLocator(List::of, new PrivateControlMarkerInspector());

        assertThatThrownBy(locator::locate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Expected exactly one compatible private control plane, found 0");
    }

    @Test
    void rejectsAmbiguousCompatiblePrivateControlPlanes() throws Exception {
        MBeanServer first = createCompatibleServer("runtime-a");
        MBeanServer second = createCompatibleServer("runtime-b");
        try {
            PrivateControlLocator locator = new PrivateControlLocator(
                    () -> List.of(first, second), new PrivateControlMarkerInspector());

            assertThatThrownBy(locator::locate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Expected exactly one compatible private control plane, found 2");
        } finally {
            MBeanServerFactory.releaseMBeanServer(second);
            MBeanServerFactory.releaseMBeanServer(first);
        }
    }

    @Test
    void fullScanProvesExactCountAfterThreeCompatibleServersAndCallsGetAttributesExactlyOnceEach() throws Exception {
        MBeanServer serverA = createCompatibleServer("runtime-a");
        MBeanServer serverB = createCompatibleServer("runtime-b");
        MBeanServer serverC = createCompatibleServer("runtime-c");
        RecordingMBeanServer recordingA = new RecordingMBeanServer(serverA);
        RecordingMBeanServer recordingB = new RecordingMBeanServer(serverB);
        RecordingMBeanServer recordingC = new RecordingMBeanServer(serverC);
        try {
            PrivateControlLocator locator = new PrivateControlLocator(
                    () -> List.of(recordingA, recordingB, recordingC), new PrivateControlMarkerInspector());

            assertThatThrownBy(locator::locate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Expected exactly one compatible private control plane, found 3");

            assertThat(recordingA.getAttributesCallCount).isEqualTo(1);
            assertThat(recordingB.getAttributesCallCount).isEqualTo(1);
            assertThat(recordingC.getAttributesCallCount).isEqualTo(1);
            assertThat(recordingA.getAttributeCallCount).isZero();
            assertThat(recordingB.getAttributeCallCount).isZero();
            assertThat(recordingC.getAttributeCallCount).isZero();
        } finally {
            MBeanServerFactory.releaseMBeanServer(serverC);
            MBeanServerFactory.releaseMBeanServer(serverB);
            MBeanServerFactory.releaseMBeanServer(serverA);
        }
    }

    @Test
    void wrongProtocolIdYieldsFoundZeroWithReason() throws Exception {
        MBeanServer server = createServerWithAttributes(replace(defaultAttributes("runtime-a"),
                "ProtocolId", "wrong-protocol"), true);
        try {
            assertLocatorMessage(server,
                    "Expected exactly one compatible private control plane, found 0 (PROTOCOL_ID_MISMATCH=1)");
        } finally {
            MBeanServerFactory.releaseMBeanServer(server);
        }
    }

    @Test
    void wrongProtocolVersionYieldsFoundZeroWithReason() throws Exception {
        MBeanServer server = createServerWithAttributes(replace(defaultAttributes("runtime-a"),
                "ProtocolVersion", 99), true);
        try {
            assertLocatorMessage(server,
                    "Expected exactly one compatible private control plane, found 0 (VERSION_MISMATCH=1)");
        } finally {
            MBeanServerFactory.releaseMBeanServer(server);
        }
    }

    @Test
    void nullRuntimeInstanceIdYieldsRuntimeIdMissing() throws Exception {
        MBeanServer server = createServerWithAttributes(replace(defaultAttributes("runtime-a"),
                "RuntimeInstanceId", null), true);
        try {
            assertLocatorMessage(server,
                    "Expected exactly one compatible private control plane, found 0 (RUNTIME_ID_MISSING=1)");
        } finally {
            MBeanServerFactory.releaseMBeanServer(server);
        }
    }

    @Test
    void blankRuntimeInstanceIdYieldsRuntimeIdBlank() throws Exception {
        MBeanServer server = createServerWithAttributes(replace(defaultAttributes("runtime-a"),
                "RuntimeInstanceId", "   "), true);
        try {
            assertLocatorMessage(server,
                    "Expected exactly one compatible private control plane, found 0 (RUNTIME_ID_BLANK=1)");
        } finally {
            MBeanServerFactory.releaseMBeanServer(server);
        }
    }

    @Test
    void writerObjectNameMismatchYieldsFoundZeroWithReason() throws Exception {
        MBeanServer server = createServerWithAttributes(replace(defaultAttributes("runtime-a"),
                "WriterObjectName", "space.br1440.platform.tracing:name=Wrong,type=Control"), true);
        try {
            assertLocatorMessage(server,
                    "Expected exactly one compatible private control plane, found 0 (WRITER_NAME_MISMATCH=1)");
        } finally {
            MBeanServerFactory.releaseMBeanServer(server);
        }
    }

    @Test
    void lifecycleNotReadyYieldsFoundZeroWithReason() throws Exception {
        MBeanServer server = createServerWithAttributes(replace(defaultAttributes("runtime-a"),
                "LifecycleState", "MARKER_REGISTERED"), true);
        try {
            assertLocatorMessage(server,
                    "Expected exactly one compatible private control plane, found 0 (LIFECYCLE_NOT_READY=1)");
        } finally {
            MBeanServerFactory.releaseMBeanServer(server);
        }
    }

    @Test
    void markerSignatureMismatchYieldsFoundZeroWithReason() throws Exception {
        MBeanServer server = createServerWithAttributes(replace(defaultAttributes("runtime-a"),
                "MarkerSignature", "WRONG_SIGNATURE"), true);
        try {
            assertLocatorMessage(server,
                    "Expected exactly one compatible private control plane, found 0 (SIGNATURE_MISMATCH=1)");
        } finally {
            MBeanServerFactory.releaseMBeanServer(server);
        }
    }

    @Test
    void writerNotRegisteredYieldsFoundZeroWithReason() throws Exception {
        MBeanServer server = createServerWithAttributes(defaultAttributes("runtime-a"), false);
        try {
            assertLocatorMessage(server,
                    "Expected exactly one compatible private control plane, found 0 (WRITER_NOT_REGISTERED=1)");
        } finally {
            MBeanServerFactory.releaseMBeanServer(server);
        }
    }

    @Test
    void missingAttributeYieldsIncompleteDescriptorAndFoundZero() throws Exception {
        List<Attribute> attributes = defaultAttributes("runtime-a");
        attributes.removeIf(attribute -> attribute.getName().equals("WriterObjectName"));
        MBeanServer server = createServerWithAttributes(attributes, true);
        try {
            assertLocatorMessage(server,
                    "Expected exactly one compatible private control plane, found 0 (INCOMPLETE_DESCRIPTOR=1)");
        } finally {
            MBeanServerFactory.releaseMBeanServer(server);
        }
    }

    @Test
    void duplicateAttributeNameYieldsMalformedDescriptor() throws Exception {
        List<Attribute> attributes = defaultAttributes("runtime-a");
        attributes.add(new Attribute("ProtocolId", PrivateControlLocator.PROTOCOL_ID));
        MBeanServer server = createServerWithAttributes(attributes, true);
        try {
            assertLocatorMessage(server,
                    "Expected exactly one compatible private control plane, found 0 (MALFORMED_DESCRIPTOR=1)");
        } finally {
            MBeanServerFactory.releaseMBeanServer(server);
        }
    }

    @Test
    void unknownAttributeNameYieldsMalformedDescriptor() throws Exception {
        List<Attribute> attributes = defaultAttributes("runtime-a");
        attributes.add(new Attribute("UnknownField", "value"));
        MBeanServer server = createServerWithAttributes(attributes, true);
        try {
            assertLocatorMessage(server,
                    "Expected exactly one compatible private control plane, found 0 (MALFORMED_DESCRIPTOR=1)");
        } finally {
            MBeanServerFactory.releaseMBeanServer(server);
        }
    }

    @Test
    void wrongRuntimeTypeYieldsMalformedDescriptor() throws Exception {
        MBeanServer server = createServerWithAttributes(replace(defaultAttributes("runtime-a"),
                "ProtocolVersion", "1"), true);
        try {
            assertLocatorMessage(server,
                    "Expected exactly one compatible private control plane, found 0 (MALFORMED_DESCRIPTOR=1)");
        } finally {
            MBeanServerFactory.releaseMBeanServer(server);
        }
    }

    @Test
    void permutedAttributeOrderIsAcceptedAsCompatible() throws Exception {
        List<Attribute> attributes = defaultAttributes("runtime-a");
        List<Attribute> reversed = new ArrayList<>(attributes);
        java.util.Collections.reverse(reversed);
        MBeanServer server = createServerWithAttributes(reversed, true);
        try {
            PrivateControlLocator.LocatedPrivateControl located =
                    new PrivateControlLocator(() -> List.of(server), new PrivateControlMarkerInspector()).locate();
            assertThat(located.runtimeInstanceId()).isEqualTo("runtime-a");
        } finally {
            MBeanServerFactory.releaseMBeanServer(server);
        }
    }

    @Test
    void securityExceptionFromSourceIsTerminalNotSilentEmpty() {
        MBeanServerSource failingSource = () -> {
            throw new SecurityException("denied");
        };
        PrivateControlLocator locator = new PrivateControlLocator(failingSource, new PrivateControlMarkerInspector());

        assertThatThrownBy(locator::locate)
                .isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(SecurityException.class);
    }

    @Test
    void securityExceptionOnIsRegisteredBeforeCompatibleServerIsTerminal() throws Exception {
        ObjectName markerName = new ObjectName(PrivateControlLocator.MARKER_NAME);
        MBeanServer failingDelegate = MBeanServerFactory.createMBeanServer();
        MBeanServer failing = new FaultInjectingMBeanServer(
                failingDelegate, markerName, new SecurityException("denied"), null);
        MBeanServer compatible = createCompatibleServer("runtime-a");
        try {
            PrivateControlLocator locator = new PrivateControlLocator(
                    () -> List.of(failing, compatible), new PrivateControlMarkerInspector());

            assertThatThrownBy(locator::locate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasCauseInstanceOf(SecurityException.class);
        } finally {
            MBeanServerFactory.releaseMBeanServer(compatible);
            MBeanServerFactory.releaseMBeanServer(failingDelegate);
        }
    }

    @Test
    void reflectionExceptionAfterOneCompatibleServerIsTerminalAndDoesNotReturnFound() throws Exception {
        MBeanServer compatible = createCompatibleServer("runtime-a");
        ObjectName markerName = new ObjectName(PrivateControlLocator.MARKER_NAME);
        MBeanServer brokenDelegate = MBeanServerFactory.createMBeanServer();
        MBeanServer broken = new FaultInjectingMBeanServer(
                brokenDelegate, markerName, null, new ReflectionException(new Exception("boom")));
        try {
            PrivateControlLocator locator = new PrivateControlLocator(
                    () -> List.of(compatible, broken), new PrivateControlMarkerInspector());

            assertThatThrownBy(locator::locate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasCauseInstanceOf(ReflectionException.class);
        } finally {
            MBeanServerFactory.releaseMBeanServer(brokenDelegate);
            MBeanServerFactory.releaseMBeanServer(compatible);
        }
    }

    @Test
    void runtimeMBeanExceptionFromGetAttributesIsTerminal() throws Exception {
        ObjectName markerName = new ObjectName(PrivateControlLocator.MARKER_NAME);
        MBeanServer brokenDelegate = MBeanServerFactory.createMBeanServer();
        MBeanServer broken = new FaultInjectingMBeanServer(
                brokenDelegate, markerName, null, new RuntimeMBeanException(new RuntimeException("boom")));
        try {
            PrivateControlLocator locator = new PrivateControlLocator(
                    () -> List.of(broken), new PrivateControlMarkerInspector());

            assertThatThrownBy(locator::locate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasCauseInstanceOf(RuntimeMBeanException.class);
        } finally {
            MBeanServerFactory.releaseMBeanServer(brokenDelegate);
        }
    }

    @Test
    void runtimeErrorExceptionFromGetAttributesIsTerminal() throws Exception {
        ObjectName markerName = new ObjectName(PrivateControlLocator.MARKER_NAME);
        MBeanServer brokenDelegate = MBeanServerFactory.createMBeanServer();
        MBeanServer broken = new FaultInjectingMBeanServer(
                brokenDelegate, markerName, null, new RuntimeErrorException(new Error("boom")));
        try {
            PrivateControlLocator locator = new PrivateControlLocator(
                    () -> List.of(broken), new PrivateControlMarkerInspector());

            assertThatThrownBy(locator::locate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasCauseInstanceOf(RuntimeErrorException.class);
        } finally {
            MBeanServerFactory.releaseMBeanServer(brokenDelegate);
        }
    }

    @Test
    void instanceNotFoundExceptionIsDefinitiveRejectionAndScanContinues() throws Exception {
        ObjectName markerName = new ObjectName(PrivateControlLocator.MARKER_NAME);
        MBeanServer disappearedDelegate = MBeanServerFactory.createMBeanServer();
        MBeanServer disappeared = new FaultInjectingMBeanServer(
                disappearedDelegate, markerName, null, new InstanceNotFoundException("gone"));
        MBeanServer compatible = createCompatibleServer("runtime-a");
        try {
            PrivateControlLocator locator = new PrivateControlLocator(
                    () -> List.of(disappeared, compatible), new PrivateControlMarkerInspector());

            PrivateControlLocator.LocatedPrivateControl located = locator.locate();
            assertThat(located.runtimeInstanceId()).isEqualTo("runtime-a");
        } finally {
            MBeanServerFactory.releaseMBeanServer(compatible);
            MBeanServerFactory.releaseMBeanServer(disappearedDelegate);
        }
    }

    @Test
    void repeatedRunsWithSameRejectionsProduceByteIdenticalDiagnostics() throws Exception {
        MBeanServer wrongProtocol = createServerWithAttributes(replace(defaultAttributes("runtime-a"),
                "ProtocolId", "wrong-protocol"), true);
        MBeanServer wrongVersion = createServerWithAttributes(replace(defaultAttributes("runtime-b"),
                "ProtocolVersion", 99), true);
        try {
            PrivateControlLocator locator = new PrivateControlLocator(
                    () -> List.of(wrongProtocol, wrongVersion), new PrivateControlMarkerInspector());

            String first = catchLocatorMessage(locator);
            String second = catchLocatorMessage(locator);

            assertThat(first).isEqualTo(second)
                    .isEqualTo("Expected exactly one compatible private control plane, found 0 "
                            + "(PROTOCOL_ID_MISMATCH=1, VERSION_MISMATCH=1)");
        } finally {
            MBeanServerFactory.releaseMBeanServer(wrongVersion);
            MBeanServerFactory.releaseMBeanServer(wrongProtocol);
        }
    }

    @Test
    void productionNoArgConstructorUsesFactoryRetainedServers() throws Exception {
        MBeanServer server = createCompatibleServer("runtime-a");
        try {
            PrivateControlLocator.LocatedPrivateControl located = new PrivateControlLocator().locate();
            assertThat(located.runtimeInstanceId()).isEqualTo("runtime-a");
        } finally {
            MBeanServerFactory.releaseMBeanServer(server);
        }
    }

    // ── тестовые фикстуры ────────────────────────────────────────────────────

    private static void assertLocatorMessage(MBeanServer server, String expectedMessage) {
        PrivateControlLocator locator =
                new PrivateControlLocator(() -> List.of(server), new PrivateControlMarkerInspector());
        assertThatThrownBy(locator::locate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(expectedMessage);
    }

    private static String catchLocatorMessage(PrivateControlLocator locator) {
        try {
            locator.locate();
            throw new AssertionError("locate() должен был выбросить IllegalStateException");
        } catch (IllegalStateException expected) {
            return expected.getMessage();
        }
    }

    private static MBeanServer createCompatibleServer(String runtimeInstanceId) throws Exception {
        return createServerWithAttributes(defaultAttributes(runtimeInstanceId), true);
    }

    private static MBeanServer createServerWithAttributes(List<Attribute> markerAttributes, boolean registerWriter)
            throws Exception {
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        server.registerMBean(new ReadOnlyDynamicMBean(markerAttributes), new ObjectName(PrivateControlLocator.MARKER_NAME));
        if (registerWriter) {
            server.registerMBean(new ReadOnlyDynamicMBean(List.of()), new ObjectName(PrivateControlLocator.WRITER_NAME));
        }
        return server;
    }

    /** Канонический набор шести совместимых атрибутов маркера в порядке объявления протокола. */
    private static List<Attribute> defaultAttributes(String runtimeInstanceId) {
        List<Attribute> attributes = new ArrayList<>();
        attributes.add(new Attribute("ProtocolId", PrivateControlLocator.PROTOCOL_ID));
        attributes.add(new Attribute("ProtocolVersion", PrivateControlLocator.PROTOCOL_VERSION));
        attributes.add(new Attribute("RuntimeInstanceId", runtimeInstanceId));
        attributes.add(new Attribute("WriterObjectName", PrivateControlLocator.WRITER_NAME));
        attributes.add(new Attribute("LifecycleState", PrivateControlLocator.LIFECYCLE_WRITER_READY));
        attributes.add(new Attribute("MarkerSignature", PrivateControlLocator.MARKER_SIGNATURE));
        return attributes;
    }

    /** Заменяет значение одного атрибута по имени, сохраняя остальные и порядок списка. */
    private static List<Attribute> replace(List<Attribute> attributes, String name, Object value) {
        List<Attribute> result = new ArrayList<>();
        for (Attribute attribute : attributes) {
            result.add(attribute.getName().equals(name) ? new Attribute(name, value) : attribute);
        }
        return result;
    }

    /**
     * Read-only {@link DynamicMBean}, возвращающий из {@code getAttributes} ровно переданный
     * список — вне зависимости от запрошенных имён. Это позволяет реалистично воспроизвести
     * документированный silent-omit, duplicate/unknown имена и произвольный порядок без мока
     * {@link MBeanServer}.
     */
    private static final class ReadOnlyDynamicMBean implements DynamicMBean {

        private final List<Attribute> attributes;
        private final MBeanInfo mBeanInfo;

        private ReadOnlyDynamicMBean(List<Attribute> attributes) {
            this.attributes = List.copyOf(attributes);
            this.mBeanInfo = new MBeanInfo(
                    getClass().getName(),
                    "Тестовая read-only модель private control plane",
                    new MBeanAttributeInfo[0],
                    null,
                    new MBeanOperationInfo[0],
                    null);
        }

        @Override
        public Object getAttribute(String attribute) {
            for (Attribute candidate : attributes) {
                if (candidate.getName().equals(attribute)) {
                    return candidate.getValue();
                }
            }
            return null;
        }

        @Override
        public void setAttribute(Attribute attribute) {
            throw new UnsupportedOperationException("MBean доступен только для чтения");
        }

        @Override
        public AttributeList getAttributes(String[] requestedAttributes) {
            AttributeList result = new AttributeList();
            result.asList().addAll(attributes);
            return result;
        }

        @Override
        public AttributeList setAttributes(AttributeList attributes) {
            return new AttributeList();
        }

        @Override
        public Object invoke(String actionName, Object[] params, String[] signature)
                throws ReflectionException {
            throw new ReflectionException(new NoSuchMethodException(actionName), "Операции не поддерживаются");
        }

        @Override
        public MBeanInfo getMBeanInfo() {
            return mBeanInfo;
        }
    }

    /** Считает вызовы {@code getAttributes}/{@code getAttribute} на маркер для batch-контракта. */
    private static final class RecordingMBeanServer extends DelegatingMBeanServer {

        private int getAttributesCallCount;
        private int getAttributeCallCount;

        RecordingMBeanServer(MBeanServer delegate) {
            super(delegate);
        }

        @Override
        public AttributeList getAttributes(ObjectName name, String[] attributes)
                throws InstanceNotFoundException, ReflectionException {
            getAttributesCallCount++;
            return super.getAttributes(name, attributes);
        }

        @Override
        public Object getAttribute(ObjectName name, String attribute)
                throws javax.management.MBeanException, javax.management.AttributeNotFoundException,
                InstanceNotFoundException, ReflectionException {
            getAttributeCallCount++;
            return super.getAttribute(name, attribute);
        }
    }

    /**
     * Инжектирует ровно одну ошибку на конкретном маркере: либо на {@code isRegistered(marker)},
     * либо на {@code getAttributes(marker, ...)}. Оба параметра-исключения взаимоисключающие —
     * тест выбирает нужный сценарий явно.
     */
    private static final class FaultInjectingMBeanServer extends DelegatingMBeanServer {

        private final ObjectName markerName;
        private final RuntimeException isRegisteredFailure;
        private final Exception getAttributesFailure;

        FaultInjectingMBeanServer(MBeanServer delegate, ObjectName markerName,
                RuntimeException isRegisteredFailure, Exception getAttributesFailure) {
            super(delegate);
            this.markerName = markerName;
            this.isRegisteredFailure = isRegisteredFailure;
            this.getAttributesFailure = getAttributesFailure;
        }

        @Override
        public boolean isRegistered(ObjectName name) {
            if (name.equals(markerName) && isRegisteredFailure != null) {
                throw isRegisteredFailure;
            }
            return true;
        }

        @Override
        public AttributeList getAttributes(ObjectName name, String[] attributes)
                throws InstanceNotFoundException, ReflectionException {
            if (name.equals(markerName) && getAttributesFailure != null) {
                if (getAttributesFailure instanceof InstanceNotFoundException exception) {
                    throw exception;
                }
                if (getAttributesFailure instanceof ReflectionException exception) {
                    throw exception;
                }
                if (getAttributesFailure instanceof RuntimeException exception) {
                    throw exception;
                }
                throw new IllegalStateException("Unsupported injected exception type", getAttributesFailure);
            }
            return super.getAttributes(name, attributes);
        }
    }
}
