package space.br1440.platform.tracing.autoconfigure.support;

import java.lang.management.ManagementFactory;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.AttributeNotFoundException;
import javax.management.DynamicMBean;
import javax.management.MBeanInfo;
import javax.management.MBeanNotificationInfo;
import javax.management.MBeanOperationInfo;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.ReflectionException;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenDataException;
import javax.management.openmbean.OpenMBeanAttributeInfo;
import javax.management.openmbean.OpenMBeanAttributeInfoSupport;
import javax.management.openmbean.OpenMBeanConstructorInfo;
import javax.management.openmbean.OpenMBeanInfoSupport;
import javax.management.openmbean.OpenMBeanOperationInfo;
import javax.management.openmbean.OpenMBeanOperationInfoSupport;
import javax.management.openmbean.OpenMBeanParameterInfo;
import javax.management.openmbean.OpenMBeanParameterInfoSupport;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;
import javax.management.openmbean.TabularDataSupport;
import javax.management.openmbean.TabularType;

import space.br1440.platform.tracing.autoconfigure.agent.AgentInstanceIdValidator;
import space.br1440.platform.tracing.autoconfigure.agent.AgentReadinessCoherenceGuard;
import space.br1440.platform.tracing.autoconfigure.agent.AgentStartupVerifier;
import space.br1440.platform.tracing.autoconfigure.control.jmx.PlatformJmxTracingSettingsClient;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxOpenTypes;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;

public final class FakeAgentPlatformJmxPlane implements AutoCloseable {

    private static final ReentrantLock PLATFORM_ENDPOINT_LOCK = new ReentrantLock();
    private static final ObjectName READINESS_NAME = JmxWireContract.EXTENSION_READINESS_OBJECT_NAME;
    private static final ObjectName STATUS_NAME = JmxWireContract.SETTINGS_STATUS_OBJECT_NAME;
    private static final ObjectName MUTATION_NAME = JmxWireContract.SETTINGS_MUTATION_OBJECT_NAME;
    private static final TabularType ROUTES = JmxOpenTypes.ROUTE_RATIOS;

    private static final String[] COMPLETE_KEYS = {
            JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_ACCEPTED_SEQUENCE,
            JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_AGENT_INSTANCE_ID,
            JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_SOURCE_AUTHORITY,
            JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_SAMPLING_ENABLED,
            JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_SAMPLING_RATIO,
            JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_SAMPLING_ROUTE_RATIOS,
            JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_VALIDATION_LEVEL,
            JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_EXPORTER_ENABLED,
            JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_TRACE_CONTROL_HEADERS_ENABLED,
            JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_DIAGNOSTICS_LOG_LEVEL
    };
    private static final CompositeType COMPLETE = JmxOpenTypes.ACCEPTED_POLICY_SNAPSHOT;

    private static final String[] RESPONSE_KEYS = {
            JmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_ACCEPTED_SEQUENCE,
            JmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_ATTEMPT_ID,
            JmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_MESSAGE_CODE,
            JmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_OUTCOME,
            JmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_AGENT_INSTANCE_ID,
            JmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_VIOLATION_CODES
    };
    private static final CompositeType RESPONSE = JmxOpenTypes.APPLY_SNAPSHOT_RESPONSE;

    private final MBeanServer server;
    private volatile String agentInstanceId;
    private final AtomicLong acceptedSequence;
    private final AtomicLong readinessGeneration = new AtomicLong(1L);
    private final AtomicReference<String> nextApplyOutcome = new AtomicReference<>("APPLIED");

    private final AtomicInteger applyInvocationCount = new AtomicInteger(0);
    private final AtomicReference<String> lastRequestAgentInstanceId = new AtomicReference<>();

    private static final String AGENT_INSTANCE_MISMATCH = "AGENT_INSTANCE_MISMATCH";
    private static final String ACCEPTED_SEQUENCE_MISMATCH = "ACCEPTED_SEQUENCE_MISMATCH";
    private static final String INVALID_VALUE = "INVALID_VALUE";
    private static final String INTERNAL_FAILURE = "INTERNAL_FAILURE";
    private static final String NONE = "NONE";

    private FakeAgentPlatformJmxPlane(String agentInstanceId,
                                      long initialSequence,
                                      DynamicMBean status,
                                      DynamicMBean mutation) throws Exception {
        PLATFORM_ENDPOINT_LOCK.lock();

        this.server = ManagementFactory.getPlatformMBeanServer();
        this.agentInstanceId = AgentInstanceIdValidator.requireCanonical(agentInstanceId, "agentInstanceId");
        this.acceptedSequence = new AtomicLong(initialSequence);
        boolean readinessRegistered = false;
        boolean statusRegistered = false;
        try {
            server.registerMBean(
                    ReadinessJmxTestFixtures.endpoint(this::readinessSnapshot),
                    READINESS_NAME
            );

            readinessRegistered = true;
            server.registerMBean(status, STATUS_NAME);

            statusRegistered = true;
            server.registerMBean(mutation, MUTATION_NAME);
        } catch (Exception registrationFailure) {
            if (statusRegistered) {
                try {
                    server.unregisterMBean(STATUS_NAME);
                } catch (Exception cleanupFailure) {
                    registrationFailure.addSuppressed(cleanupFailure);
                }
            }

            if (readinessRegistered) {
                try {
                    server.unregisterMBean(READINESS_NAME);
                } catch (Exception cleanupFailure) {
                    registrationFailure.addSuppressed(cleanupFailure);
                }
            }

            PLATFORM_ENDPOINT_LOCK.unlock();
            throw registrationFailure;
        }
    }

    public static FakeAgentPlatformJmxPlane compliant(String agentInstanceId, long initialSequence) throws Exception {
        FakeAgentPlatformJmxPlane[] holder = new FakeAgentPlatformJmxPlane[1];
        FakeAgentPlatformJmxPlane plane = new FakeAgentPlatformJmxPlane(
                agentInstanceId,
                initialSequence,
                snapshotEndpoint(() -> holder[0].encodeComplete()),
                mutationEndpoint(request -> holder[0].encodeApplyOutcome(request))
        );

        holder[0] = plane;
        return plane;
    }

    public static FakeAgentPlatformJmxPlane missingAcceptedPolicySnapshot(String agentInstanceId) throws Exception {
        return new FakeAgentPlatformJmxPlane(
                agentInstanceId,
                0,
                missingSnapshotEndpoint(),
                mutationEndpoint(request -> null)
        );
    }

    public static FakeAgentPlatformJmxPlane readFailingAcceptedPolicySnapshot(String agentInstanceId) throws Exception {
        return new FakeAgentPlatformJmxPlane(
                agentInstanceId,
                0,
                snapshotEndpoint(() -> {
                    throw new IllegalStateException("Status endpoint read failed");
                }),
                mutationEndpoint(request -> null)
        );
    }

    public static FakeAgentPlatformJmxPlane unknownAuthorityAcceptedPolicySnapshot(String agentInstanceId) throws Exception {
        FakeAgentPlatformJmxPlane[] holder = new FakeAgentPlatformJmxPlane[1];
        FakeAgentPlatformJmxPlane plane = new FakeAgentPlatformJmxPlane(
                agentInstanceId,
                0,
                snapshotEndpoint(() -> holder[0].encodeComplete("HELM")),
                mutationEndpoint(request -> holder[0].encodeApplyOutcome(request))
        );
        holder[0] = plane;
        return plane;
    }

    public static FakeAgentPlatformJmxPlane malformedApplySnapshotResponse(String agentInstanceId) throws Exception {
        CompositeType wrongType = new CompositeType(
                "totally.wrong.ResponseType.v1", "wrong", new String[]{"x"},
                new String[]{"x"}, new OpenType<?>[]{SimpleType.STRING}
        );
        CompositeData malformed = new CompositeDataSupport(wrongType, Map.of("x", "irrelevant"));

        FakeAgentPlatformJmxPlane[] holder = new FakeAgentPlatformJmxPlane[1];
        FakeAgentPlatformJmxPlane plane = new FakeAgentPlatformJmxPlane(
                agentInstanceId,
                0,
                snapshotEndpoint(() -> holder[0].encodeComplete()),
                mutationEndpoint(request -> malformed));
        holder[0] = plane;
        return plane;
    }

    public PlatformJmxTracingSettingsClient client() {
        AgentReadinessCoherenceGuard coherenceGuard = new AgentReadinessCoherenceGuard(server);
        AgentStartupVerifier startupVerifier = new AgentStartupVerifier(coherenceGuard, () -> "1.2.3");
        startupVerifier.verify(true, true, false, true);
        PlatformJmxTracingSettingsClient client = new PlatformJmxTracingSettingsClient(server, coherenceGuard);
        client.initialize();
        return client;
    }

    public long currentAcceptedSequence() {
        return acceptedSequence.get();
    }

    public int applyInvocationCount() {
        return applyInvocationCount.get();
    }

    public String lastRequestAgentInstanceId() {
        return lastRequestAgentInstanceId.get();
    }

    public void setNextApplyOutcome(String outcome) {
        nextApplyOutcome.set(outcome);
    }

    public void restartWithNewInstance(String newAgentInstanceId, long newSequence) {
        this.agentInstanceId = AgentInstanceIdValidator.requireCanonical(
                newAgentInstanceId,
                "newAgentInstanceId"
        );
        this.acceptedSequence.set(newSequence);
        this.readinessGeneration.incrementAndGet();
    }

    private CompositeData readinessSnapshot() {
        long generation = readinessGeneration.get();
        return ReadinessJmxTestFixtures.readySnapshot(
                agentInstanceId,
                generation,
                "fake-agent-batch-" + generation
        );
    }

    private CompositeData encodeComplete() {
        return encodeComplete("CONFIG_SERVER");
    }

    private CompositeData encodeComplete(String sourceAuthority) {
        try {
            return new CompositeDataSupport(COMPLETE, COMPLETE_KEYS, new Object[]{
                    acceptedSequence.get(), agentInstanceId, sourceAuthority,
                    true, 0.1,
                    new TabularDataSupport(ROUTES),
                    "WARNING", true, true,
                    "INFO"
            });
        } catch (OpenDataException e) {
            throw new IllegalStateException(e);
        }
    }

    private CompositeData encodeApplyOutcome(CompositeData request) {
        applyInvocationCount.incrementAndGet();
        String requestAgentInstanceId = (String) request.get(JmxWireContract.FIELD_APPLY_SNAPSHOT_REQUEST_EXPECTED_AGENT_INSTANCE_ID);
        String requestAttemptId = (String) request.get(JmxWireContract.FIELD_APPLY_SNAPSHOT_REQUEST_ATTEMPT_ID);
        lastRequestAgentInstanceId.set(requestAgentInstanceId);

        if (!agentInstanceId.equals(requestAgentInstanceId)) {
            try {
                return new CompositeDataSupport(RESPONSE, RESPONSE_KEYS, new Object[]{
                        acceptedSequence.get(), requestAttemptId, AGENT_INSTANCE_MISMATCH, "REJECTED",
                        agentInstanceId, new String[]{AGENT_INSTANCE_MISMATCH}
                });
            } catch (OpenDataException e) {
                throw new IllegalStateException(e);
            }
        }

        String outcome = nextApplyOutcome.get();
        long reportedSequence = "APPLIED".equals(outcome)
                ? acceptedSequence.incrementAndGet()
                : acceptedSequence.get();
        String messageCode = switch (outcome) {
            case "APPLIED", "UNCHANGED" -> NONE;
            case "REJECTED" -> INVALID_VALUE;
            case "STALE" -> ACCEPTED_SEQUENCE_MISMATCH;
            case "FAILED" -> INTERNAL_FAILURE;
            default -> throw new IllegalArgumentException("Unsupported fake apply outcome: " + outcome);
        };

        String[] violationCodes = NONE.equals(messageCode)
                ? new String[0]
                : new String[]{messageCode};

        try {
            return new CompositeDataSupport(RESPONSE, RESPONSE_KEYS, new Object[]{
                    reportedSequence, requestAttemptId, messageCode, outcome, agentInstanceId, violationCodes
            });
        } catch (OpenDataException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() throws Exception {
        Exception failure = null;
        try {
            for (ObjectName name : new ObjectName[]{MUTATION_NAME, STATUS_NAME, READINESS_NAME}) {
                try {
                    if (server.isRegistered(name)) {
                        server.unregisterMBean(name);
                    }
                } catch (Exception cleanupFailure) {
                    if (failure == null) {
                        failure = cleanupFailure;
                    } else {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
            }
        } finally {
            PLATFORM_ENDPOINT_LOCK.unlock();
        }

        if (failure != null) {
            throw failure;
        }
    }

    private static DynamicMBean snapshotEndpoint(Supplier<Object> snapshotSupplier) {
        return snapshotEndpoint(snapshotSupplier, true);
    }

    private static DynamicMBean missingSnapshotEndpoint() {
        return snapshotEndpoint(() -> null, false);
    }

    private static DynamicMBean snapshotEndpoint(Supplier<Object> snapshotSupplier, boolean declared) {
        return new DynamicMBean() {

            @Override
            public Object getAttribute(String attribute) throws AttributeNotFoundException {
                if (!declared
                        || !JmxWireContract.ATTRIBUTE_SETTINGS_STATUS_ACCEPTED_POLICY_SNAPSHOT
                        .equals(attribute)) {
                    throw new AttributeNotFoundException("Unknown status attribute: " + attribute);
                }
                return snapshotSupplier.get();
            }

            @Override
            public void setAttribute(Attribute attribute) throws AttributeNotFoundException {
                throw new AttributeNotFoundException("Status endpoint is read-only");
            }

            @Override
            public AttributeList getAttributes(String[] attributes) {
                AttributeList result = new AttributeList();
                if (!declared || attributes == null) {
                    return result;
                }

                for (String attribute : attributes) {
                    if (JmxWireContract.ATTRIBUTE_SETTINGS_STATUS_ACCEPTED_POLICY_SNAPSHOT
                            .equals(attribute)) {
                        result.add(new Attribute(attribute, snapshotSupplier.get()));
                    }
                }

                return result;
            }

            @Override
            public AttributeList setAttributes(AttributeList attributes) {
                return new AttributeList();
            }

            @Override
            public Object invoke(String actionName, Object[] params, String[] signature)
                    throws ReflectionException {
                throw new ReflectionException(
                        new NoSuchMethodException(actionName), "Status endpoint exposes no operations");
            }

            @Override
            public MBeanInfo getMBeanInfo() {
                OpenMBeanAttributeInfo[] attributes = declared
                        ? new OpenMBeanAttributeInfo[]{new OpenMBeanAttributeInfoSupport(
                        JmxWireContract.ATTRIBUTE_SETTINGS_STATUS_ACCEPTED_POLICY_SNAPSHOT,
                        "Accepted tracing policy snapshot",
                        JmxOpenTypes.ACCEPTED_POLICY_SNAPSHOT,
                        true,
                        false,
                        false)}
                        : new OpenMBeanAttributeInfo[0];

                return new OpenMBeanInfoSupport(
                        getClass().getName(),
                        "Fake tracing settings status endpoint",
                        attributes,
                        new OpenMBeanConstructorInfo[0],
                        new OpenMBeanOperationInfo[0],
                        new MBeanNotificationInfo[0]);
            }
        };
    }

    private static DynamicMBean mutationEndpoint(Function<CompositeData, Object> mutationHandler) {
        return new DynamicMBean() {

            @Override
            public Object getAttribute(String attribute) throws AttributeNotFoundException {
                throw new AttributeNotFoundException("Mutation endpoint exposes no attributes");
            }

            @Override
            public void setAttribute(Attribute attribute) throws AttributeNotFoundException {
                throw new AttributeNotFoundException("Mutation endpoint exposes no attributes");
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
            public Object invoke(String actionName, Object[] params, String[] signature) throws ReflectionException {
                boolean validSignature = signature != null
                        && signature.length == 1
                        && CompositeData.class.getName().equals(signature[0]);

                boolean validParameters = params != null
                        && params.length == 1
                        && params[0] instanceof CompositeData;

                if (!JmxWireContract.OPERATION_SETTINGS_MUTATION_APPLY_SNAPSHOT.equals(actionName)
                        || !validSignature
                        || !validParameters) {
                    throw new ReflectionException(new NoSuchMethodException(actionName), "Unknown mutation operation");
                }

                return mutationHandler.apply((CompositeData) params[0]);
            }

            @Override
            public MBeanInfo getMBeanInfo() {
                OpenMBeanParameterInfo parameter = new OpenMBeanParameterInfoSupport(
                        "request",
                        "Tracing settings snapshot",
                        JmxOpenTypes.APPLY_SNAPSHOT_REQUEST
                );

                OpenMBeanOperationInfo operation = new OpenMBeanOperationInfoSupport(
                        JmxWireContract.OPERATION_SETTINGS_MUTATION_APPLY_SNAPSHOT,
                        "Apply tracing settings snapshot",
                        new OpenMBeanParameterInfo[]{parameter},
                        JmxOpenTypes.APPLY_SNAPSHOT_RESPONSE,
                        MBeanOperationInfo.ACTION
                );

                return new OpenMBeanInfoSupport(
                        getClass().getName(),
                        "Fake tracing settings mutation endpoint",
                        new OpenMBeanAttributeInfo[0],
                        new OpenMBeanConstructorInfo[0],
                        new OpenMBeanOperationInfo[]{operation},
                        new MBeanNotificationInfo[0]
                );
            }
        };
    }
}
