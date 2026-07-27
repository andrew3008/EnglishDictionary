package space.br1440.platform.tracing.e2e.jmx.privateplane;

import org.springframework.context.support.GenericApplicationContext;

import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.DynamicMBean;
import javax.management.InvalidAttributeValueException;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanException;
import javax.management.MBeanInfo;
import javax.management.MBeanOperationInfo;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.ObjectName;
import javax.management.ReflectionException;
import javax.management.StandardMBean;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenDataException;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;
import javax.management.remote.JMXConnectorServer;
import javax.management.remote.JMXConnectorServerFactory;
import javax.management.remote.JMXServiceURL;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.rmi.registry.LocateRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * App-CL сторона S-03. Не зависит от Agent fixture classes и platform DTO.
 */
public final class PrivateMBeanServerProbeMain {

    private static final String MARKER_NAME =
            "space.br1440.platform.tracing.private:type=Marker,name=RuntimeControl";
    private static final String WRITER_NAME =
            "space.br1440.platform.tracing.private:type=Control,name=RuntimePolicyWriter";
    private static final String PROJECTION_NAME =
            "space.br1440.platform.tracing:type=SpikeProjection,name=PrivateControlReadModel";
    private static final String PROTOCOL_ID = "platform-tracing-private-control";
    private static final int PROTOCOL_VERSION = 1;
    private static final String MARKER_SIGNATURE = "S03_PRIVATE_PROCESS_LOCAL_V1";

    private static final AtomicInteger DISCOVERY_ATTEMPTS = new AtomicInteger();
    private static final AtomicInteger INVOCATION_ATTEMPTS = new AtomicInteger();

    private PrivateMBeanServerProbeMain() {
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "normal" : args[0];
        if ("disabled".equals(mode)) {
            disabledScenario();
            return;
        }

        Locator locator = new Locator();
        LocatedServer located = locator.locate();
        emit("appClassLoader=" + loaderName(PrivateMBeanServerProbeMain.class.getClassLoader()));
        emit("compositeDataClassLoader=" + loaderName(CompositeData.class.getClassLoader()));
        emit("M3=PASS");

        if ("agent-only".equals(mode)) {
            emit("M7=PASS");
            shutdown(located);
            return;
        }

        runCoreScenarios(locator, located);
        runSpringContextScenario();
        runJdkFactoryCharacterization();

        JMXConnectorServer remote = startRemoteConnector();
        emit("remoteUrl=" + remote.getAddress());
        emit("pid=" + ProcessHandle.current().pid());
        emit("READY");

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String command = reader.readLine();
            if (!"shutdown".equals(command)) {
                throw new IllegalStateException("Expected shutdown command, got: " + command);
            }
        } finally {
            remote.stop();
        }

        shutdown(located);
        emit("M5=PASS");
    }

    private static void runCoreScenarios(Locator locator, LocatedServer located) throws Exception {
        MBeanServer platform = ManagementFactory.getPlatformMBeanServer();
        ObjectName writerName = new ObjectName(WRITER_NAME);
        check(!platform.isRegistered(writerName), "private writer leaked to platform server");
        check(platform.isRegistered(new ObjectName(PROJECTION_NAME)), "read projection absent");
        emit("M1=PASS");

        MBeanInfo projection = platform.getMBeanInfo(new ObjectName(PROJECTION_NAME));
        check(List.of(projection.getAttributes()).stream().noneMatch(MBeanAttributeInfo::isWritable),
                "target projection has writable attribute");
        check(projection.getOperations().length == 0, "target projection has operations");
        emit("M12=PASS");

        String runtimeId = located.runtimeInstanceId();
        CompositeData valid = request(PROTOCOL_VERSION, runtimeId, 0L, "digest-1", true);
        CompositeData applied = invoke(located, valid);
        check("APPLIED".equals(applied.get("status")), "valid request was not applied");
        check(RESPONSE_TYPE.equals(applied.getCompositeType()), "response OpenType differs");
        emit("M4=PASS");
        emit("roundTripStatus=" + applied.get("status"));
        emit("agentWriterClassLoader=isolated-extension-classloader");

        CompositeData wrongVersion = invoke(
                located,
                request(99, runtimeId, 1L, "digest-version", false));
        check("REJECTED".equals(wrongVersion.get("status")), "wrong version not rejected");
        emit("N4=PASS");

        CompositeData wrongInstance = invoke(
                located,
                request(PROTOCOL_VERSION, "wrong-runtime", 1L, "digest-instance", false));
        check("STALE".equals(wrongInstance.get("status")), "wrong instance not stale");
        check(((Long) applied.get("sequence")) == 1L, "unexpected applied sequence");
        emit("N5=PASS");

        runMalformedScenarios(located, runtimeId);
        runDuplicateMarkerScenarios(locator);
        runPlatformSameNameScenario(locator, platform, writerName);
    }

    private static void runMalformedScenarios(LocatedServer located, String runtimeId) throws Exception {
        List<CompositeData> malformed = new ArrayList<>();
        malformed.add(new CompositeDataSupport(
                composite("WrongType", new String[]{"protocolVersion"},
                        new OpenType<?>[]{SimpleType.INTEGER}),
                new String[]{"protocolVersion"},
                new Object[]{PROTOCOL_VERSION}));
        malformed.add(new CompositeDataSupport(
                composite("MissingField", new String[]{"protocolVersion", "expectedRuntimeInstanceId"},
                        new OpenType<?>[]{SimpleType.INTEGER, SimpleType.STRING}),
                new String[]{"protocolVersion", "expectedRuntimeInstanceId"},
                new Object[]{PROTOCOL_VERSION, runtimeId}));
        malformed.add(new CompositeDataSupport(
                composite("UnknownField",
                        new String[]{"protocolVersion", "expectedRuntimeInstanceId", "expectedSequence",
                                "candidateDigest", "candidateEnabled", "unknown"},
                        new OpenType<?>[]{SimpleType.INTEGER, SimpleType.STRING, SimpleType.LONG,
                                SimpleType.STRING, SimpleType.BOOLEAN, SimpleType.STRING}),
                new String[]{"protocolVersion", "expectedRuntimeInstanceId", "expectedSequence",
                        "candidateDigest", "candidateEnabled", "unknown"},
                new Object[]{PROTOCOL_VERSION, runtimeId, 1L, "digest", false, "unknown"}));
        malformed.add(new CompositeDataSupport(
                composite("WrongOpenType", REQUEST_KEYS,
                        new OpenType<?>[]{SimpleType.STRING, SimpleType.STRING, SimpleType.LONG,
                                SimpleType.STRING, SimpleType.BOOLEAN}),
                REQUEST_KEYS,
                new Object[]{"1", runtimeId, 1L, "digest", false}));
        malformed.add(new NullRequiredCompositeData(REQUEST_TYPE, runtimeId));
        malformed.add(request(PROTOCOL_VERSION, runtimeId, 1L, "x".repeat(129), false));

        for (CompositeData candidate : malformed) {
            CompositeData response = invoke(located, candidate);
            check("REJECTED".equals(response.get("status")),
                    "malformed request escaped rejection: " + candidate.getCompositeType().getTypeName());
            check(((Long) response.get("sequence")) == 1L, "malformed request mutated state");
        }
        emit("N6=PASS");
    }

    private static void runDuplicateMarkerScenarios(Locator locator) throws Exception {
        int baseline = MBeanServerFactory.findMBeanServer(null).size();
        MBeanServer invalid = MBeanServerFactory.createMBeanServer("space.br1440.platform.tracing.private");
        try {
            registerAdversarial(invalid, "invalid-runtime", 99, "WRONG_SIGNATURE");
            LocatedServer selected = locator.locate();
            check(selected != null, "invalid marker displaced valid marker");
        } finally {
            cleanupAdversarial(invalid);
        }

        MBeanServer duplicate = MBeanServerFactory.createMBeanServer("space.br1440.platform.tracing.private");
        try {
            registerAdversarial(duplicate, "duplicate-runtime", PROTOCOL_VERSION, MARKER_SIGNATURE);
            boolean ambiguous = false;
            try {
                locator.locate();
            } catch (IllegalStateException expected) {
                ambiguous = expected.getMessage().contains("exactly one");
            }
            check(ambiguous, "two valid markers did not fail with ambiguity");
        } finally {
            cleanupAdversarial(duplicate);
        }
        check(MBeanServerFactory.findMBeanServer(null).size() == baseline,
                "adversarial servers leaked");
        emit("N1=PASS");
        emit("N2=PASS");
    }

    private static void runPlatformSameNameScenario(
            Locator locator,
            MBeanServer platform,
            ObjectName writerName) throws Exception {
        platform.registerMBean(new DummyDynamicMBean(), writerName);
        try {
            LocatedServer selected = locator.locate();
            check(selected.server() != platform, "platform server selected by writer name alone");
        } finally {
            platform.unregisterMBean(writerName);
        }
        emit("N3=PASS");
    }

    private static void runSpringContextScenario() {
        try (GenericApplicationContext first = context();
             GenericApplicationContext second = context()) {
            first.getBean(Locator.class).locate();
            second.getBean(Locator.class).locate();
            long markers = MBeanServerFactory.findMBeanServer(null).stream()
                    .filter(server -> server.isRegistered(objectName(MARKER_NAME)))
                    .count();
            check(markers == 1L, "multiple Spring contexts duplicated Agent marker");
        }
        emit("M6=PASS");
    }

    private static GenericApplicationContext context() {
        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBean(Locator.class);
        context.refresh();
        return context;
    }

    private static void runJdkFactoryCharacterization() throws Exception {
        int baseline = MBeanServerFactory.findMBeanServer(null).size();
        MBeanServer created = MBeanServerFactory.createMBeanServer("duplicate-domain");
        MBeanServer sameDomain = MBeanServerFactory.createMBeanServer("duplicate-domain");
        MBeanServer unretained = MBeanServerFactory.newMBeanServer("duplicate-domain");
        check(MBeanServerFactory.findMBeanServer(null).contains(created),
                "createMBeanServer is not discoverable");
        check(!MBeanServerFactory.findMBeanServer(null).contains(unretained),
                "newMBeanServer unexpectedly discoverable");
        check(MBeanServerFactory.findMBeanServer("duplicate-domain").isEmpty(),
                "findMBeanServer matched default domain instead of MBeanServerId");

        ObjectName retainedName = new ObjectName("s03:type=Retained");
        created.registerMBean(new DummyDynamicMBean(), retainedName);
        MBeanServerFactory.releaseMBeanServer(created);
        check(created.isRegistered(retainedName), "release unexpectedly unregistered MBean");
        check(!MBeanServerFactory.findMBeanServer(null).contains(created),
                "released server remains factory-discoverable");
        created.unregisterMBean(retainedName);
        MBeanServerFactory.releaseMBeanServer(sameDomain);
        check(MBeanServerFactory.findMBeanServer(null).size() == baseline,
                "JDK characterization leaked factory-retained server");

        for (int index = 0; index < 5; index++) {
            MBeanServer cycle = MBeanServerFactory.createMBeanServer("cycle-domain");
            ObjectName cycleName = new ObjectName("s03:type=Cycle,index=" + index);
            cycle.registerMBean(new DummyDynamicMBean(), cycleName);
            cycle.unregisterMBean(cycleName);
            MBeanServerFactory.releaseMBeanServer(cycle);
            check(MBeanServerFactory.findMBeanServer(null).size() == baseline,
                    "factory count leaked at cycle " + index);
        }
        emit("jdkCreateDiscoverable=true");
        emit("jdkNewDiscoverable=false");
        emit("jdkFindUsesMBeanServerId=true");
        emit("jdkDuplicateDefaultDomainsAllowed=true");
        emit("jdkReleaseDoesNotUnregister=true");
        emit("M10=PASS");
    }

    private static JMXConnectorServer startRemoteConnector() throws Exception {
        int port;
        try (ServerSocket reservation = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = reservation.getLocalPort();
        }
        LocateRegistry.createRegistry(port);
        JMXServiceURL url = new JMXServiceURL(
                "service:jmx:rmi:///jndi/rmi://127.0.0.1:" + port + "/jmxrmi");
        JMXConnectorServer connector = JMXConnectorServerFactory.newJMXConnectorServer(
                url,
                Map.of(),
                ManagementFactory.getPlatformMBeanServer());
        connector.start();
        return connector;
    }

    private static void disabledScenario() {
        System.setProperty("platform.tracing.mode", "DISABLED");
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.refresh();
            check(!context.containsBean("privateMBeanServerLocator"),
                    "disabled context contains locator");
        }
        check(DISCOVERY_ATTEMPTS.get() == 0, "disabled mode performed discovery");
        check(INVOCATION_ATTEMPTS.get() == 0, "disabled mode performed invocation");
        emit("disabledDiscoveryAttempts=0");
        emit("disabledInvocationAttempts=0");
        emit("M8=PASS");
    }

    private static CompositeData invoke(LocatedServer located, CompositeData request) throws Exception {
        INVOCATION_ATTEMPTS.incrementAndGet();
        return (CompositeData) located.server().invoke(
                new ObjectName(WRITER_NAME),
                "apply",
                new Object[]{request},
                new String[]{CompositeData.class.getName()});
    }

    private static void shutdown(LocatedServer located) throws Exception {
        located.server().invoke(
                new ObjectName(WRITER_NAME),
                "shutdown",
                new Object[0],
                new String[0]);
        check(!MBeanServerFactory.findMBeanServer(null).contains(located.server()),
                "private server remains factory-discoverable");
        check(!located.server().isRegistered(new ObjectName(WRITER_NAME)),
                "private writer remains registered");
        check(!located.server().isRegistered(new ObjectName(MARKER_NAME)),
                "private marker remains registered");
    }

    private static CompositeData request(
            int protocolVersion,
            String expectedRuntimeInstanceId,
            long expectedSequence,
            String digest,
            boolean enabled) throws OpenDataException {
        return new CompositeDataSupport(
                REQUEST_TYPE,
                REQUEST_KEYS,
                new Object[]{
                        protocolVersion,
                        expectedRuntimeInstanceId,
                        expectedSequence,
                        digest,
                        enabled
                });
    }

    private static void registerAdversarial(
            MBeanServer server,
            String runtimeId,
            int version,
            String signature) throws Exception {
        server.registerMBean(new DummyDynamicMBean(), new ObjectName(WRITER_NAME));
        server.registerMBean(
                new StandardMBean(
                        new AdversarialMarker(runtimeId, version, signature),
                        AdversarialMarkerMBean.class,
                        false),
                new ObjectName(MARKER_NAME));
    }

    private static void cleanupAdversarial(MBeanServer server) throws Exception {
        if (server.isRegistered(new ObjectName(WRITER_NAME))) {
            server.unregisterMBean(new ObjectName(WRITER_NAME));
        }
        if (server.isRegistered(new ObjectName(MARKER_NAME))) {
            server.unregisterMBean(new ObjectName(MARKER_NAME));
        }
        MBeanServerFactory.releaseMBeanServer(server);
    }

    private static ObjectName objectName(String value) {
        try {
            return new ObjectName(value);
        } catch (Exception failure) {
            throw new IllegalArgumentException(value, failure);
        }
    }

    private static CompositeType composite(
            String name,
            String[] keys,
            OpenType<?>[] types) {
        try {
            return new CompositeType(name, name, keys, keys, types);
        } catch (OpenDataException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static String loaderName(ClassLoader loader) {
        return loader == null ? "bootstrap" : loader.getClass().getName();
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static void emit(String value) {
        System.out.println("S03_APP:" + value);
        System.out.flush();
    }

    public static final class Locator {

        LocatedServer locate() {
            DISCOVERY_ATTEMPTS.incrementAndGet();
            List<LocatedServer> matches = new ArrayList<>();
            ObjectName markerName = objectName(MARKER_NAME);
            ObjectName writerName = objectName(WRITER_NAME);
            for (MBeanServer server : MBeanServerFactory.findMBeanServer(null)) {
                if (!server.isRegistered(markerName)) {
                    continue;
                }
                try {
                    String protocolId = (String) server.getAttribute(markerName, "ProtocolId");
                    int version = (Integer) server.getAttribute(markerName, "ProtocolVersion");
                    String runtimeId = (String) server.getAttribute(markerName, "RuntimeInstanceId");
                    String advertisedWriter = (String) server.getAttribute(markerName, "WriterObjectName");
                    String lifecycle = (String) server.getAttribute(markerName, "LifecycleState");
                    String signature = (String) server.getAttribute(markerName, "MarkerSignature");
                    if (PROTOCOL_ID.equals(protocolId)
                            && version == PROTOCOL_VERSION
                            && runtimeId != null
                            && !runtimeId.isBlank()
                            && WRITER_NAME.equals(advertisedWriter)
                            && "WRITER_READY".equals(lifecycle)
                            && MARKER_SIGNATURE.equals(signature)
                            && server.isRegistered(writerName)) {
                        matches.add(new LocatedServer(server, runtimeId));
                    }
                } catch (Exception ignored) {
                    // Невалидный или несовместимый marker не является кандидатом.
                }
            }
            if (matches.size() != 1) {
                throw new IllegalStateException(
                        "Expected exactly one valid private MBeanServer, found " + matches.size());
            }
            return matches.get(0);
        }
    }

    public interface AdversarialMarkerMBean {

        String getProtocolId();

        int getProtocolVersion();

        String getRuntimeInstanceId();

        String getWriterObjectName();

        String getLifecycleState();

        String getMarkerSignature();
    }

    private record AdversarialMarker(String runtimeId, int version, String signature)
            implements AdversarialMarkerMBean {

        @Override
        public String getProtocolId() {
            return PROTOCOL_ID;
        }

        @Override
        public int getProtocolVersion() {
            return version;
        }

        @Override
        public String getRuntimeInstanceId() {
            return runtimeId;
        }

        @Override
        public String getWriterObjectName() {
            return WRITER_NAME;
        }

        @Override
        public String getLifecycleState() {
            return "WRITER_READY";
        }

        @Override
        public String getMarkerSignature() {
            return signature;
        }
    }

    private record LocatedServer(MBeanServer server, String runtimeInstanceId) {
    }

    private static final class DummyDynamicMBean implements DynamicMBean {

        private static final MBeanInfo INFO = new MBeanInfo(
                DummyDynamicMBean.class.getName(),
                "S-03 dummy",
                new MBeanAttributeInfo[]{new MBeanAttributeInfo(
                        "Status", String.class.getName(), "status", true, false, false)},
                null,
                new MBeanOperationInfo[0],
                null);

        @Override
        public Object getAttribute(String attribute) {
            return "READ_ONLY";
        }

        @Override
        public void setAttribute(Attribute attribute) throws InvalidAttributeValueException {
            throw new InvalidAttributeValueException("read only");
        }

        @Override
        public AttributeList getAttributes(String[] attributes) {
            AttributeList result = new AttributeList();
            for (String attribute : attributes) {
                result.add(new Attribute(attribute, "READ_ONLY"));
            }
            return result;
        }

        @Override
        public AttributeList setAttributes(AttributeList attributes) {
            return new AttributeList();
        }

        @Override
        public Object invoke(String actionName, Object[] params, String[] signature)
                throws MBeanException, ReflectionException {
            throw new ReflectionException(new NoSuchMethodException(actionName));
        }

        @Override
        public MBeanInfo getMBeanInfo() {
            return INFO;
        }
    }

    private static final class NullRequiredCompositeData implements CompositeData {

        private final CompositeType type;
        private final String runtimeId;

        private NullRequiredCompositeData(CompositeType type, String runtimeId) {
            this.type = type;
            this.runtimeId = runtimeId;
        }

        @Override
        public CompositeType getCompositeType() {
            return type;
        }

        @Override
        public Object get(String key) {
            return "candidateDigest".equals(key) ? null : switch (key) {
                case "protocolVersion" -> PROTOCOL_VERSION;
                case "expectedRuntimeInstanceId" -> runtimeId;
                case "expectedSequence" -> 1L;
                case "candidateEnabled" -> false;
                default -> null;
            };
        }

        @Override
        public Object[] getAll(String[] keys) {
            return java.util.Arrays.stream(keys).map(this::get).toArray();
        }

        @Override
        public boolean containsKey(String key) {
            return type.containsKey(key);
        }

        @Override
        public boolean containsValue(Object value) {
            return false;
        }

        @Override
        public java.util.Collection<?> values() {
            return List.of(PROTOCOL_VERSION, runtimeId, 1L, false);
        }
    }

    private static final String[] REQUEST_KEYS = {
            "protocolVersion",
            "expectedRuntimeInstanceId",
            "expectedSequence",
            "candidateDigest",
            "candidateEnabled"
    };
    private static final CompositeType REQUEST_TYPE = composite(
            "PlatformTracingPrivateRequestV1",
            REQUEST_KEYS,
            new OpenType<?>[]{
                    SimpleType.INTEGER,
                    SimpleType.STRING,
                    SimpleType.LONG,
                    SimpleType.STRING,
                    SimpleType.BOOLEAN
            });
    private static final CompositeType RESPONSE_TYPE = composite(
            "PlatformTracingPrivateResponseV1",
            new String[]{"status", "runtimeInstanceId", "sequence", "message", "enabled"},
            new OpenType<?>[]{
                    SimpleType.STRING,
                    SimpleType.STRING,
                    SimpleType.LONG,
                    SimpleType.STRING,
                    SimpleType.BOOLEAN
            });
}
