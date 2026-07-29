package space.br1440.platform.tracing.otel.javaagent.control.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.management.MBeanInfo;
import javax.management.MBeanOperationInfo;
import javax.management.MBeanServer;
import javax.management.ReflectionException;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.TabularDataSupport;
import java.util.Map;
import java.util.UUID;

/**
 * Exact {@code DynamicMBean.invoke()} dispatch contract for {@link PrivateControlWriter}
 * (P4 audit checklist item #6, remaining-blocker closure item #2).
 * <p>
 * Canonical contract, chosen and fixed by this test class:
 * <ul>
 *   <li>{@code readAcceptedComplete}: {@code params} and {@code signature} MUST each be
 *       either {@code null} or a zero-length array (both spellings of "no arguments" are
 *       equivalent, matching {@link javax.management.MBeanServer#invoke} semantics). Any
 *       non-empty {@code params} or non-empty {@code signature} is rejected.</li>
 *   <li>{@code applySnapshot}: {@code params} MUST be a single-element array whose only
 *       element is a {@link CompositeData}; {@code signature} MUST be a single-element
 *       array containing exactly {@code CompositeData.class.getName()}. {@code null} is
 *       rejected for both.</li>
 *   <li>Any other operation name, or a call that does not exactly match one of the two
 *       contracts above, throws {@link ReflectionException} wrapping
 *       {@link NoSuchMethodException} — this must never diverge from what
 *       {@link #getMBeanInfo()} publishes.</li>
 * </ul>
 */
class PrivateControlWriterInvokeContractTest {

    private String runtimeId;
    private AcceptedPolicyStateStore store;
    private PrivateControlMBeanServerOwner owner;
    private MBeanServer server;

    @BeforeEach
    void setUp() throws Exception {
        runtimeId = UUID.randomUUID().toString();
        store = new AcceptedPolicyStateStore(runtimeId, EffectiveTracingPolicy.of(
                true, 1.0, Map.of(), true, false, true, true, PolicyLogLevel.INFO));
        owner = new PrivateControlMBeanServerOwner(runtimeId, store, new PolicyValidationConstraints(true));
        server = owner.initialize();
    }

    @AfterEach
    void tearDown() {
        owner.close();
    }

    // ── readAcceptedComplete: canonical and accepted variants ──────────────────

    @Test
    void readAcceptedComplete_canonicalEmptyArraysSucceeds() throws Exception {
        Object result = server.invoke(PrivateControlObjectNames.WRITER, "readAcceptedComplete",
                new Object[0], new String[0]);
        assertThat(result).isInstanceOf(CompositeData.class);
    }

    @Test
    void readAcceptedComplete_nullParamsAndSignatureSucceeds() throws Exception {
        Object result = server.invoke(PrivateControlObjectNames.WRITER, "readAcceptedComplete", null, null);
        assertThat(result).isInstanceOf(CompositeData.class);
    }

    // ── readAcceptedComplete: rejected variants ─────────────────────────────────

    @Test
    void readAcceptedComplete_rejectsOneUnexpectedArgument() {
        assertThatThrownBy(() -> server.invoke(PrivateControlObjectNames.WRITER, "readAcceptedComplete",
                new Object[]{"unexpected"}, new String[]{"java.lang.String"}))
                .isInstanceOf(ReflectionException.class);
    }

    @Test
    void readAcceptedComplete_rejectsNonEmptySignatureWithEmptyParams() {
        assertThatThrownBy(() -> server.invoke(PrivateControlObjectNames.WRITER, "readAcceptedComplete",
                new Object[0], new String[]{"java.lang.String"}))
                .isInstanceOf(ReflectionException.class);
    }

    @Test
    void readAcceptedComplete_rejectsNonEmptyParamsWithEmptySignature() {
        assertThatThrownBy(() -> server.invoke(PrivateControlObjectNames.WRITER, "readAcceptedComplete",
                new Object[]{"unexpected"}, new String[0]))
                .isInstanceOf(ReflectionException.class);
    }

    // ── applySnapshot: canonical variant ─────────────────────────────────────────

    @Test
    void applySnapshot_canonicalSingleCompositeDataArgumentSucceeds() throws Exception {
        Object result = server.invoke(PrivateControlObjectNames.WRITER, "applySnapshot",
                new Object[]{validRequest()}, new String[]{CompositeData.class.getName()});
        assertThat(result).isInstanceOf(CompositeData.class);
    }

    // ── applySnapshot: rejected variants ─────────────────────────────────────────

    @Test
    void applySnapshot_rejectsNullParams() {
        assertThatThrownBy(() -> server.invoke(PrivateControlObjectNames.WRITER, "applySnapshot",
                null, new String[]{CompositeData.class.getName()}))
                .isInstanceOf(ReflectionException.class);
    }

    @Test
    void applySnapshot_rejectsZeroParams() {
        assertThatThrownBy(() -> server.invoke(PrivateControlObjectNames.WRITER, "applySnapshot",
                new Object[0], new String[0]))
                .isInstanceOf(ReflectionException.class);
    }

    @Test
    void applySnapshot_rejectsTwoParams() {
        assertThatThrownBy(() -> server.invoke(PrivateControlObjectNames.WRITER, "applySnapshot",
                new Object[]{validRequest(), validRequest()},
                new String[]{CompositeData.class.getName(), CompositeData.class.getName()}))
                .isInstanceOf(ReflectionException.class);
    }

    @Test
    void applySnapshot_rejectsWrongParameterType() {
        assertThatThrownBy(() -> server.invoke(PrivateControlObjectNames.WRITER, "applySnapshot",
                new Object[]{"not-a-composite-data"}, new String[]{"java.lang.String"}))
                .isInstanceOf(ReflectionException.class);
    }

    @Test
    void applySnapshot_rejectsNullSignature() {
        assertThatThrownBy(() -> server.invoke(PrivateControlObjectNames.WRITER, "applySnapshot",
                new Object[]{validRequest()}, null))
                .isInstanceOf(ReflectionException.class);
    }

    @Test
    void applySnapshot_rejectsZeroLengthSignature() {
        assertThatThrownBy(() -> server.invoke(PrivateControlObjectNames.WRITER, "applySnapshot",
                new Object[]{validRequest()}, new String[0]))
                .isInstanceOf(ReflectionException.class);
    }

    @Test
    void applySnapshot_rejectsTwoSignatureEntries() {
        assertThatThrownBy(() -> server.invoke(PrivateControlObjectNames.WRITER, "applySnapshot",
                new Object[]{validRequest()},
                new String[]{CompositeData.class.getName(), CompositeData.class.getName()}))
                .isInstanceOf(ReflectionException.class);
    }

    @Test
    void applySnapshot_rejectsWrongSignatureType() {
        assertThatThrownBy(() -> server.invoke(PrivateControlObjectNames.WRITER, "applySnapshot",
                new Object[]{validRequest()}, new String[]{"java.lang.Object"}))
                .isInstanceOf(ReflectionException.class);
    }

    @Test
    void applySnapshot_rejectsCorrectParamsWithMalformedSignature() {
        // params are exactly right, but the caller lied about the signature: reject before
        // even attempting to decode the payload, matching the published MBeanOperationInfo.
        assertThatThrownBy(() -> server.invoke(PrivateControlObjectNames.WRITER, "applySnapshot",
                new Object[]{validRequest()}, new String[]{"java.lang.String"}))
                .isInstanceOf(ReflectionException.class);
    }

    // ── unknown operation ─────────────────────────────────────────────────────────

    @Test
    void unknownOperation_throwsReflectionExceptionWrappingNoSuchMethodException() {
        assertThatThrownBy(() -> server.invoke(PrivateControlObjectNames.WRITER, "deletePolicy",
                new Object[0], new String[0]))
                .isInstanceOf(ReflectionException.class)
                .extracting(Throwable::getCause)
                .isInstanceOf(NoSuchMethodException.class);
    }

    // ── MBeanInfo / dispatch parity ───────────────────────────────────────────────

    @Test
    void mBeanInfoOperationCountAndShapeMatchesDispatch() throws Exception {
        MBeanInfo info = server.getMBeanInfo(PrivateControlObjectNames.WRITER);

        assertThat(info.getOperations()).hasSize(2);
        assertThat(info.getAttributes()).isEmpty();
        assertThat(info.getConstructors()).isEmpty();
        assertThat(info.getNotifications()).isEmpty();

        MBeanOperationInfo read = operation(info, "readAcceptedComplete");
        assertThat(read.getSignature()).isEmpty();
        assertThat(read.getReturnType()).isEqualTo(CompositeData.class.getName());
        assertThat(read.getImpact()).isEqualTo(MBeanOperationInfo.INFO);

        MBeanOperationInfo apply = operation(info, "applySnapshot");
        assertThat(apply.getSignature()).hasSize(1);
        assertThat(apply.getSignature()[0].getType()).isEqualTo(CompositeData.class.getName());
        assertThat(apply.getReturnType()).isEqualTo(CompositeData.class.getName());
        assertThat(apply.getImpact()).isEqualTo(MBeanOperationInfo.ACTION);
    }

    private static MBeanOperationInfo operation(MBeanInfo info, String name) {
        for (MBeanOperationInfo op : info.getOperations()) {
            if (op.getName().equals(name)) return op;
        }
        throw new AssertionError("No such operation: " + name);
    }

    private CompositeData validRequest() throws Exception {
        EffectiveTracingPolicy policy = EffectiveTracingPolicy.of(
                true, 0.5, Map.of(), true, false, true, true, PolicyLogLevel.INFO);
        TabularDataSupport routes = new TabularDataSupport(PrivateControlWireTypes.ROUTES);
        CompositeData policyData = new CompositeDataSupport(
                PrivateControlWireTypes.POLICY,
                PrivateControlWireTypes.POLICY_KEYS,
                new Object[]{
                        policy.diagnosticsLogLevel().name(),
                        policy.exporterEnabled(),
                        policy.samplingEnabled(),
                        policy.samplingRatio(),
                        routes,
                        policy.traceControlHeadersEnabled(),
                        policy.validationEnabled(),
                        policy.validationStrict()
                });
        return new CompositeDataSupport(
                PrivateControlWireTypes.REQUEST,
                PrivateControlWireTypes.REQUEST_KEYS,
                new Object[]{
                        "REFRESH",
                        UUID.randomUUID().toString(),
                        EffectiveTracingPolicyDigest.sha256(policy),
                        0L,
                        runtimeId,
                        PrivateControlObjectNames.PROTOCOL_VERSION,
                        policyData,
                        "CONFIG_SERVER",
                        "revision-1"
                });
    }
}
