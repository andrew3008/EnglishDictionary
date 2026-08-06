package space.br1440.platform.tracing.autoconfigure.support;

import space.br1440.platform.tracing.autoconfigure.control.jmx.PrivateJmxTracingPolicySnapshotClient;
import space.br1440.platform.tracing.autoconfigure.control.jmx.PrivateControlLocator;

import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.DynamicMBean;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanInfo;
import javax.management.MBeanOperationInfo;
import javax.management.MBeanParameterInfo;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.ObjectName;
import javax.management.ReflectionException;
import javax.management.openmbean.ArrayType;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenDataException;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;
import javax.management.openmbean.TabularData;
import javax.management.openmbean.TabularDataSupport;
import javax.management.openmbean.TabularType;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Test-only simulation of the Agent-side private control plane, registered on a
 * dedicated {@link MBeanServer} discoverable process-wide via
 * {@link MBeanServerFactory#findMBeanServer(Object)} — exactly how the real
 * {@code PrivateControlLocator} discovers the real Agent.
 * <p>
 * This is NOT a wire-parity fixture: the {@code readAcceptedComplete} /
 * {@code applySnapshot} {@link CompositeType} shapes here are hand-built to match
 * {@code PrivateControlWireTypes}'s (Spring-side) package-private schema constants,
 * because this class plays the role of the external Agent process, which by
 * definition cannot share a production DTO or test bridge with the Spring module.
 * Real cross-module wire parity is proven independently by
 * {@code AgentToSpringWireCodecParityTest} (Agent encoder vs Spring decoder).
 * <p>
 * Used to prove management child-context wiring/readiness (actuator package) and
 * Config Server refresh-coalescer regression behaviour (configserver package) against
 * the real {@link PrivateJmxTracingPolicySnapshotClient} — not wire-format correctness.
 */
public final class FakeAgentPrivateControlPlane implements AutoCloseable {

    private static final String MARKER_NAME =
            "space.br1440.platform.tracing:type=PrivateMarker,name=PlatformPrivateControlPlane";
    private static final String WRITER_NAME =
            "space.br1440.platform.tracing:name=PlatformControlProtocol,type=Control";
    private static final String PROTOCOL_ID = "platform-tracing-private-control";
    private static final int PROTOCOL_VERSION = 1;
    private static final String MARKER_SIGNATURE = "PLATFORM_PRIVATE_CONTROL_V1";

    private static final CompositeType ROUTE_ROW = composite(
            "space.br1440.platform.tracing.control.RouteRatio.v1",
            new String[]{"ratio", "routePrefix"},
            new OpenType<?>[]{SimpleType.DOUBLE, SimpleType.STRING});
    private static final TabularType ROUTES = table(
            "space.br1440.platform.tracing.control.RouteRatios.v1", ROUTE_ROW);

    private static final String[] COMPLETE_KEYS = {
            "acceptedSequence", "runtimeInstanceId", "samplingEnabled", "samplingRatio",
            "samplingRouteRatios", "validationEnabled", "validationStrict", "exporterEnabled",
            "traceControlHeadersEnabled", "diagnosticsLogLevel"
    };
    private static final CompositeType COMPLETE = composite(
            "AcceptedCompleteSnapshot/v1",
            COMPLETE_KEYS,
            new OpenType<?>[]{
                    SimpleType.LONG, SimpleType.STRING, SimpleType.BOOLEAN, SimpleType.DOUBLE,
                    ROUTES, SimpleType.BOOLEAN, SimpleType.BOOLEAN, SimpleType.BOOLEAN,
                    SimpleType.BOOLEAN, SimpleType.STRING
            });

    private static final String[] RESPONSE_KEYS = {
            "acceptedSequence", "attemptId", "messageCode", "outcome", "payloadDigest",
            "protocolVersion", "restartRequired", "restartRequiredKeys", "runtimeInstanceId",
            "violationCodes"
    };
    private static final CompositeType RESPONSE = composite(
            "space.br1440.platform.tracing.control.ApplySnapshotResponse.v1",
            RESPONSE_KEYS,
            new OpenType<?>[]{
                    SimpleType.LONG, SimpleType.STRING, SimpleType.STRING, SimpleType.STRING,
                    SimpleType.STRING, SimpleType.INTEGER, SimpleType.BOOLEAN,
                    arrayOfStrings(), SimpleType.STRING, arrayOfStrings()
            });

    private final MBeanServer server;
    private volatile String runtimeInstanceId;
    private final AtomicLong acceptedSequence;
    private final AtomicReference<String> nextApplyOutcome = new AtomicReference<>("APPLIED");
    /** Consumed once by the next {@code applySnapshot}; then falls back to {@link #nextApplyOutcome}. */
    private final AtomicReference<String> oneShotApplyOutcome = new AtomicReference<>(null);

    // ── test-only request-capture extension (interleaving / atomic-base regression) ────
    //
    // Added to prove P1 atomic-expected-base behaviour end-to-end: the fake now decodes the
    // caller-supplied (runtimeInstanceId, acceptedSequence) pair from the real applySnapshot
    // wire request instead of ignoring it, and compares the runtimeInstanceId half against its
    // own current identity. This is the minimal test-only extension called for by the plan —
    // NOT a full re-implementation of the Agent's AcceptedPolicyCoordinator: sequence-staleness
    // itself is still fully scripted via {@link #setNextApplyOutcome} / {@link
    // #setOneShotApplyOutcome}; only the runtime-identity mismatch check is real.
    private final AtomicInteger applyInvocationCount = new AtomicInteger(0);
    private final AtomicReference<String> lastRequestRuntimeInstanceId = new AtomicReference<>();
    private final AtomicReference<Long> lastRequestAcceptedSequence = new AtomicReference<>();
    private final AtomicReference<String> lastResponseMessageCode = new AtomicReference<>("");

    private static final String RUNTIME_INSTANCE_MISMATCH = "RUNTIME_INSTANCE_MISMATCH";

    private FakeAgentPrivateControlPlane(
            MBeanServer server, String runtimeInstanceId, long initialSequence, DynamicMBean writer)
            throws Exception {
        this.server = server;
        this.runtimeInstanceId = runtimeInstanceId;
        this.acceptedSequence = new AtomicLong(initialSequence);
        registerMarker(runtimeInstanceId);
        server.registerMBean(writer, new ObjectName(WRITER_NAME));
    }

    private void registerMarker(String runtimeInstanceId) throws Exception {
        server.registerMBean(
                new ReadOnlyMarker(Map.of(
                        "ProtocolId", PROTOCOL_ID,
                        "ProtocolVersion", PROTOCOL_VERSION,
                        "RuntimeInstanceId", runtimeInstanceId,
                        "WriterObjectName", WRITER_NAME,
                        "LifecycleState", "WRITER_READY",
                        "MarkerSignature", MARKER_SIGNATURE)),
                new ObjectName(MARKER_NAME));
    }

    /** A fully compliant Agent: readAcceptedComplete + applySnapshot both work canonically. */
    public static FakeAgentPrivateControlPlane compliant(String runtimeInstanceId, long initialSequence)
            throws Exception {
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        FakeAgentPrivateControlPlane[] holder = new FakeAgentPrivateControlPlane[1];
        DynamicMBean writer = new DynamicMBean() {
            @Override
            public Object getAttribute(String attribute) { return null; }

            @Override
            public void setAttribute(Attribute attribute) { }

            @Override
            public AttributeList getAttributes(String[] attributes) { return new AttributeList(); }

            @Override
            public AttributeList setAttributes(AttributeList attributes) { return new AttributeList(); }

            @Override
            public Object invoke(String actionName, Object[] params, String[] signature)
                    throws ReflectionException {
                if ("readAcceptedComplete".equals(actionName)) {
                    return holder[0].encodeComplete();
                }
                if ("applySnapshot".equals(actionName)) {
                    return holder[0].encodeApplyOutcome((CompositeData) params[0]);
                }
                throw new ReflectionException(new NoSuchMethodException(actionName));
            }

            @Override
            public MBeanInfo getMBeanInfo() {
                return new MBeanInfo(
                        getClass().getName(), "fake compliant agent writer",
                        new MBeanAttributeInfo[0], null,
                        new MBeanOperationInfo[]{
                                new MBeanOperationInfo("readAcceptedComplete", "read",
                                        new MBeanParameterInfo[0],
                                        CompositeData.class.getName(), MBeanOperationInfo.INFO),
                                new MBeanOperationInfo("applySnapshot", "apply",
                                        new MBeanParameterInfo[]{
                                                new MBeanParameterInfo(
                                                        "request", CompositeData.class.getName(), "request")
                                        },
                                        CompositeData.class.getName(), MBeanOperationInfo.ACTION)
                        },
                        null);
            }
        };
        FakeAgentPrivateControlPlane plane =
                new FakeAgentPrivateControlPlane(server, runtimeInstanceId, initialSequence, writer);
        holder[0] = plane;
        return plane;
    }

    /**
     * An Agent build that never implemented PROPOSED_PRIV_R07 — no
     * {@code readAcceptedComplete} operation at all (pre-{@code 00b987a} behaviour).
     */
    public static FakeAgentPrivateControlPlane missingReadAcceptedComplete(String runtimeInstanceId)
            throws Exception {
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        DynamicMBean writer = new DynamicMBean() {
            @Override
            public Object getAttribute(String attribute) { return null; }

            @Override
            public void setAttribute(Attribute attribute) { }

            @Override
            public AttributeList getAttributes(String[] attributes) { return new AttributeList(); }

            @Override
            public AttributeList setAttributes(AttributeList attributes) { return new AttributeList(); }

            @Override
            public Object invoke(String actionName, Object[] params, String[] signature)
                    throws ReflectionException {
                throw new ReflectionException(new NoSuchMethodException(actionName));
            }

            @Override
            public MBeanInfo getMBeanInfo() {
                return new MBeanInfo(getClass().getName(), "fake pre-PRIV-R07 agent writer",
                        new MBeanAttributeInfo[0], null, new MBeanOperationInfo[0], null);
            }
        };
        return new FakeAgentPrivateControlPlane(server, runtimeInstanceId, 0, writer);
    }

    /**
     * An Agent build whose {@code readAcceptedComplete} returns a structurally
     * malformed response (wrong {@link CompositeType} name) — simulating a
     * protocol-incompatible Agent build that nonetheless exposes the operation name.
     */
    public static FakeAgentPrivateControlPlane malformedReadAcceptedComplete(String runtimeInstanceId)
            throws Exception {
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        CompositeType wrongType = new CompositeType(
                "totally.wrong.Type.v1", "wrong", new String[]{"x"},
                new String[]{"x"}, new OpenType<?>[]{SimpleType.STRING});
        CompositeData malformed = new CompositeDataSupport(wrongType, Map.of("x", "irrelevant"));
        DynamicMBean writer = new DynamicMBean() {
            @Override
            public Object getAttribute(String attribute) { return null; }

            @Override
            public void setAttribute(Attribute attribute) { }

            @Override
            public AttributeList getAttributes(String[] attributes) { return new AttributeList(); }

            @Override
            public AttributeList setAttributes(AttributeList attributes) { return new AttributeList(); }

            @Override
            public Object invoke(String actionName, Object[] params, String[] signature)
                    throws ReflectionException {
                if ("readAcceptedComplete".equals(actionName)) {
                    return malformed;
                }
                throw new ReflectionException(new NoSuchMethodException(actionName));
            }

            @Override
            public MBeanInfo getMBeanInfo() {
                return new MBeanInfo(getClass().getName(), "fake malformed-response agent writer",
                        new MBeanAttributeInfo[0], null, new MBeanOperationInfo[0], null);
            }
        };
        return new FakeAgentPrivateControlPlane(server, runtimeInstanceId, 0, writer);
    }

    /** Real, initialized Spring-side client bound to this fake Agent's MBeanServer. */
    public PrivateJmxTracingPolicySnapshotClient client() {
        PrivateJmxTracingPolicySnapshotClient client =
                new PrivateJmxTracingPolicySnapshotClient(new PrivateControlLocator());
        client.initialize();
        return client;
    }

    public long currentAcceptedSequence() {
        return acceptedSequence.get();
    }

    /** Сколько раз реально был вызван {@code applySnapshot} (независимо от исхода). */
    public int applyInvocationCount() {
        return applyInvocationCount.get();
    }

    /** {@code expectedRuntimeInstanceId} из последнего полученного запроса applySnapshot. */
    public String lastRequestRuntimeInstanceId() {
        return lastRequestRuntimeInstanceId.get();
    }

    /** {@code expectedAcceptedSequence} из последнего полученного запроса applySnapshot. */
    public long lastRequestAcceptedSequence() {
        return lastRequestAcceptedSequence.get();
    }

    /** {@code messageCode} из последнего отправленного ответа applySnapshot. */
    public String lastResponseMessageCode() {
        return lastResponseMessageCode.get();
    }

    /** Configures the outcome the next (and subsequent) {@code applySnapshot} calls report. */
    public void setNextApplyOutcome(String outcome) {
        nextApplyOutcome.set(outcome);
    }

    /**
     * Configures the outcome for exactly one {@code applySnapshot} call.
     * After that call, subsequent outcomes come from {@link #setNextApplyOutcome(String)}.
     */
    public void setOneShotApplyOutcome(String outcome) {
        oneShotApplyOutcome.set(outcome);
    }

    /**
     * Simulates an Agent-process restart observed through the private control plane:
     * a new {@code runtimeInstanceId} and a reset accepted-sequence counter, without
     * changing the {@link MBeanServer}/{@link ObjectName} identity a caller has already
     * located — matching how a real restarted Agent re-attaches under the same
     * process-local marker contract but a fresh identity and sequence baseline.
     */
    public void restartWithNewInstance(String newRuntimeInstanceId, long newSequence) throws Exception {
        server.unregisterMBean(new ObjectName(MARKER_NAME));
        this.runtimeInstanceId = newRuntimeInstanceId;
        this.acceptedSequence.set(newSequence);
        registerMarker(newRuntimeInstanceId);
    }

    private CompositeData encodeComplete() {
        try {
            return new CompositeDataSupport(COMPLETE, COMPLETE_KEYS, new Object[]{
                    acceptedSequence.get(), runtimeInstanceId, true, 0.1,
                    new TabularDataSupport(ROUTES), true, false, true, true, "INFO"
            });
        } catch (OpenDataException e) {
            throw new IllegalStateException(e);
        }
    }

    private CompositeData encodeApplyOutcome(CompositeData request) {
        applyInvocationCount.incrementAndGet();
        String requestRuntimeInstanceId = (String) request.get("expectedRuntimeInstanceId");
        long requestAcceptedSequence = (Long) request.get("expectedAcceptedSequence");
        lastRequestRuntimeInstanceId.set(requestRuntimeInstanceId);
        lastRequestAcceptedSequence.set(requestAcceptedSequence);

        // Единственная часть протокола, которую этот fake реально проверяет, а не просто
        // разыгрывает по сценарию: если runtimeInstanceId в запросе не совпадает с текущим —
        // это тот самый P1-кейс (Agent перезапустился между read и write), и applySnapshot
        // обязан отвергнуть попытку как STALE, не трогая принятую политику и sequence.
        if (!runtimeInstanceId.equals(requestRuntimeInstanceId)) {
            lastResponseMessageCode.set(RUNTIME_INSTANCE_MISMATCH);
            try {
                return new CompositeDataSupport(RESPONSE, RESPONSE_KEYS, new Object[]{
                        acceptedSequence.get(), "fake-attempt", RUNTIME_INSTANCE_MISMATCH, "STALE", "", 1,
                        false, new String[0], runtimeInstanceId, new String[0]
                });
            } catch (OpenDataException e) {
                throw new IllegalStateException(e);
            }
        }

        String oneShot = oneShotApplyOutcome.getAndSet(null);
        String outcome = oneShot != null ? oneShot : nextApplyOutcome.get();
        long reportedSequence = "APPLIED".equals(outcome)
                ? acceptedSequence.incrementAndGet()
                : acceptedSequence.get();
        lastResponseMessageCode.set("");
        try {
            return new CompositeDataSupport(RESPONSE, RESPONSE_KEYS, new Object[]{
                    reportedSequence, "fake-attempt", "", outcome, "", 1, false, new String[0],
                    runtimeInstanceId, new String[0]
            });
        } catch (OpenDataException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        MBeanServerFactory.releaseMBeanServer(server);
    }

    private static CompositeType composite(String name, String[] keys, OpenType<?>[] types) {
        try {
            return new CompositeType(name, name, keys, keys, types);
        } catch (OpenDataException e) {
            throw new IllegalStateException(e);
        }
    }

    private static TabularType table(String name, CompositeType row) {
        try {
            return new TabularType(name, name, row, new String[]{"routePrefix"});
        } catch (OpenDataException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ArrayType<String[]> arrayOfStrings() {
        try {
            return ArrayType.getArrayType(SimpleType.STRING);
        } catch (OpenDataException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class ReadOnlyMarker implements DynamicMBean {
        private final Map<String, Object> attributes;
        private final MBeanInfo mBeanInfo;

        ReadOnlyMarker(Map<String, Object> attributes) {
            this.attributes = Map.copyOf(attributes);
            this.mBeanInfo = new MBeanInfo(
                    getClass().getName(), "fake private control marker",
                    attributes.entrySet().stream()
                            .map(entry -> new MBeanAttributeInfo(
                                    entry.getKey(), entry.getValue().getClass().getName(),
                                    entry.getKey(), true, false, false))
                            .toArray(MBeanAttributeInfo[]::new),
                    null, new MBeanOperationInfo[0], null);
        }

        @Override
        public Object getAttribute(String attribute) { return attributes.get(attribute); }

        @Override
        public void setAttribute(Attribute attribute) {
            throw new UnsupportedOperationException("read-only");
        }

        @Override
        public AttributeList getAttributes(String[] requested) {
            AttributeList result = new AttributeList();
            for (String attribute : requested) {
                if (attributes.containsKey(attribute)) {
                    result.add(new Attribute(attribute, attributes.get(attribute)));
                }
            }
            return result;
        }

        @Override
        public AttributeList setAttributes(AttributeList attributes) { return new AttributeList(); }

        @Override
        public Object invoke(String actionName, Object[] params, String[] signature)
                throws ReflectionException {
            throw new ReflectionException(new NoSuchMethodException(actionName));
        }

        @Override
        public MBeanInfo getMBeanInfo() { return mBeanInfo; }
    }
}
