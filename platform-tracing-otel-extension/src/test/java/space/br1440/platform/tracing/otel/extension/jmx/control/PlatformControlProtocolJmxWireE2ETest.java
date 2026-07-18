package space.br1440.platform.tracing.otel.extension.jmx.control;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocol;
import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocolKeys;
import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocolOperation;
import space.br1440.platform.tracing.core.control.protocol.RuntimePolicyControlHandler;
import space.br1440.platform.tracing.core.control.protocol.RuntimeControlMutationPolicy;
import space.br1440.platform.tracing.otel.extension.control.JmxRuntimePolicyApplier;
import space.br1440.platform.tracing.otel.extension.control.ReadAppliedStateHandler;
import space.br1440.platform.tracing.otel.extension.jmx.PlatformTracingObjectNames;
import space.br1440.platform.tracing.otel.extension.jmx.sampling.PlatformSamplingControl;
import space.br1440.platform.tracing.otel.extension.jmx.validation.PlatformValidationControl;
import space.br1440.platform.tracing.otel.extension.processor.ValidatingSpanProcessor;
import space.br1440.platform.tracing.otel.extension.sampler.SamplerStateHolder;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.StandardMBean;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;
import java.lang.management.ManagementFactory;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end JMX wire test.
 *
 * <p>All interactions go through the real {@link MBeanServer}:
 * <ol>
 *   <li>Build a {@link CompositeData} wire payload.</li>
 *   <li>Invoke {@code applyPolicy(CompositeData)} via
 *       {@link MBeanServer#invoke}.</li>
 *   <li>Verify observable side-effects on live holders <em>and</em>
 *       the returned result String.</li>
 * </ol>
 *
 * <p>No mocking framework is used.  The test registers a fresh
 * {@link PlatformControlProtocolMBean} instance under a private
 * {@code ObjectName} (different from the production name to avoid
 * conflicts with other tests) and unregisters it in {@code @AfterEach}.
 */
class PlatformControlProtocolJmxWireE2ETest {

    // -------------------------------------------------------------------------
    // Infrastructure
    // -------------------------------------------------------------------------

    private static final ObjectName TEST_NAME;
    static {
        try {
            TEST_NAME = new ObjectName(
                    "space.br1440.platform.tracing.test:type=Control,name=E2E");
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private MBeanServer              mbeanServer;
    private SamplerStateHolder       samplerHolder;
    private ValidatingSpanProcessor  validatingProcessor;
    private LongAdder                counter;
    private PlatformControlProtocolMBean mbean;

    @BeforeEach
    void setUp() throws Exception {
        mbeanServer = ManagementFactory.getPlatformMBeanServer();

        samplerHolder = new SamplerStateHolder(
                true,
                List.of(),
                List.of(),
                Collections.emptyMap(),
                0.1d);

        validatingProcessor = new ValidatingSpanProcessor(false, true);
        counter             = new LongAdder();

        PlatformSamplingControl  samplingControl  =
                new PlatformSamplingControl(samplerHolder, null, counter);
        PlatformValidationControl validationControl =
                new PlatformValidationControl(validatingProcessor, counter);

        JmxRuntimePolicyApplier applier =
                new JmxRuntimePolicyApplier(samplingControl, validationControl);

        RuntimePolicyControlHandler handler =
                new RuntimePolicyControlHandler(
                        applier, RuntimeControlMutationPolicy.startupConfigured(true));

        ReadAppliedStateHandler readHandler =
                new ReadAppliedStateHandler(samplerHolder, validatingProcessor);

        mbean = new PlatformControlProtocolMBean(
                TracingControlProtocol.current(),
                handler,
                readHandler,
                counter);

        if (mbeanServer.isRegistered(TEST_NAME)) {
            mbeanServer.unregisterMBean(TEST_NAME);
        }
        mbeanServer.registerMBean(new StandardMBean(mbean, PlatformControlProtocolMXBean.class, false), TEST_NAME);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mbeanServer.isRegistered(TEST_NAME)) {
            mbeanServer.unregisterMBean(TEST_NAME);
        }
    }

    // -------------------------------------------------------------------------
    // Wire helpers
    // -------------------------------------------------------------------------

    /**
     * Builds a minimal {@link CompositeData} for
     * {@code APPLY_RUNTIME_POLICY} with the given extra keys/values.
     */
    private CompositeData buildPayload(String[] extraKeys, Object[] extraVals)
            throws Exception {
        int base = 2;
        int total = base + extraKeys.length;
        String[] keys  = new String[total];
        String[] descs = new String[total];
        OpenType<?>[] types = new OpenType<?>[total];
        Object[] vals  = new Object[total];

        keys[0]  = TracingControlProtocolKeys.CONTRACT_VERSION;
        descs[0] = "contractVersion";
        types[0] = SimpleType.INTEGER;
        vals[0]  = 1;

        keys[1]  = TracingControlProtocolKeys.OPERATION;
        descs[1] = "operation";
        types[1] = SimpleType.STRING;
        vals[1]  = TracingControlProtocolOperation.APPLY_RUNTIME_POLICY.wireValue();

        for (int i = 0; i < extraKeys.length; i++) {
            keys[base + i]  = extraKeys[i];
            descs[base + i] = extraKeys[i];
            // Infer type from value
            Object v = extraVals[i];
            if (v instanceof Double) {
                types[base + i] = SimpleType.DOUBLE;
            } else if (v instanceof Boolean) {
                types[base + i] = SimpleType.BOOLEAN;
            } else {
                types[base + i] = SimpleType.STRING;
            }
            vals[base + i] = v;
        }

        CompositeType ct = new CompositeType(
                "Payload", "wire payload", keys, descs, types);
        return new CompositeDataSupport(ct, keys, vals);
    }

    /** Invokes {@code applyPolicy(CompositeData)} on the MBean via JMX. */
    private String invokeApply(CompositeData payload) throws Exception {
        return (String) mbeanServer.invoke(
                TEST_NAME,
                "applyPolicy",
                new Object[]{payload},
                new String[]{CompositeData.class.getName()});
    }

    /** Invokes {@code readAppliedState()} on the MBean via JMX. */
    private CompositeData invokeRead() throws Exception {
        return (CompositeData) mbeanServer.invoke(
                TEST_NAME,
                "readAppliedState",
                new Object[]{},
                new String[]{});
    }

    // =========================================================================
    // Tests: APPLY_RUNTIME_POLICY — success paths
    // =========================================================================

    @Nested
    class ApplySuccess {

        @Test
        void updatesSamplingRatioViaJmx() throws Exception {
            CompositeData payload = buildPayload(
                    new String[]{TracingControlProtocolKeys.SAMPLING_RATIO},
                    new Object[]{0.55d});

            String result = invokeApply(payload);

            assertThat(result).startsWith("SUCCESS");
            assertThat(samplerHolder.current().defaultRatio()).isEqualTo(0.55d);
            assertThat(counter.sum()).isZero();
        }

        @Test
        void enablesKillSwitchViaJmx() throws Exception {
            CompositeData payload = buildPayload(
                    new String[]{TracingControlProtocolKeys.SAMPLING_KILL_SWITCH_ENABLED},
                    new Object[]{true});

            invokeApply(payload);

            assertThat(samplerHolder.current().enabled()).isFalse();
        }

        @Test
        void enablesValidationModeStrictViaJmx() throws Exception {
            CompositeData payload = buildPayload(
                    new String[]{
                            TracingControlProtocolKeys.VALIDATION_ENABLED,
                            TracingControlProtocolKeys.VALIDATION_MODE},
                    new Object[]{true, "STRICT"});

            String result = invokeApply(payload);

            assertThat(result).startsWith("SUCCESS");
            assertThat(validatingProcessor.isEnabled()).isTrue();
            assertThat(validatingProcessor.isStrict()).isTrue();
        }

        @Test
        void combinedPayloadUpdatesBothDomains() throws Exception {
            CompositeData payload = buildPayload(
                    new String[]{
                            TracingControlProtocolKeys.SAMPLING_RATIO,
                            TracingControlProtocolKeys.VALIDATION_ENABLED,
                            TracingControlProtocolKeys.VALIDATION_MODE},
                    new Object[]{0.7d, true, "LOG_ONLY"});

            String result = invokeApply(payload);

            assertThat(result).startsWith("SUCCESS");
            assertThat(samplerHolder.current().defaultRatio()).isEqualTo(0.7d);
            assertThat(validatingProcessor.isEnabled()).isTrue();
            assertThat(validatingProcessor.isStrict()).isFalse();
        }
    }

    @Test
    void mutationDisabledRejectsApplyWithoutChangingAppliedState() throws Exception {
        RuntimePolicyControlHandler disabledHandler = new RuntimePolicyControlHandler(
                new JmxRuntimePolicyApplier(
                        new PlatformSamplingControl(samplerHolder, null, counter),
                        new PlatformValidationControl(validatingProcessor, counter)));
        PlatformControlProtocolMBean disabledMBean = new PlatformControlProtocolMBean(
                TracingControlProtocol.current(),
                disabledHandler,
                new ReadAppliedStateHandler(samplerHolder, validatingProcessor),
                counter);
        CompositeData payload = buildPayload(
                new String[]{TracingControlProtocolKeys.SAMPLING_RATIO},
                new Object[]{0.55d});
        long versionBefore = samplerHolder.current().version();
        String sourceBefore = samplerHolder.current().source();

        assertThat(disabledMBean.readAppliedState()).isNotNull();
        String result = disabledMBean.applyPolicy(payload);

        assertThat(result).startsWith("MUTATION_REJECTED APPLY_RUNTIME_POLICY");
        assertThat(samplerHolder.current().defaultRatio()).isEqualTo(0.1d);
        assertThat(samplerHolder.current().version()).isEqualTo(versionBefore);
        assertThat(samplerHolder.current().source()).isEqualTo(sourceBefore);
    }

    // =========================================================================
    // Tests: DECODE_REJECTED
    // =========================================================================

    @Nested
    class DecodeRejected {

        @Test
        void nullPayloadIsDecodeRejected() throws Exception {
            String result = invokeApply(null);

            assertThat(result).startsWith("DECODE_REJECTED");
            assertThat(counter.sum()).isEqualTo(1);
        }

        @Test
        void unknownOperationIsDecodeRejected() throws Exception {
            String[] keys  = {
                    TracingControlProtocolKeys.CONTRACT_VERSION,
                    TracingControlProtocolKeys.OPERATION};
            String[] descs = {"contractVersion", "operation"};
            OpenType<?>[] types = {SimpleType.INTEGER, SimpleType.STRING};
            Object[] vals  = {1, "DO_WEIRD_STUFF"};
            CompositeType ct = new CompositeType(
                    "Bad", "bad", keys, descs, types);
            CompositeData badPayload = new CompositeDataSupport(ct, keys, vals);

            String result = invokeApply(badPayload);

            assertThat(result).startsWith("DECODE_REJECTED");
            assertThat(counter.sum()).isEqualTo(1);
        }

        @Test
        void decodeRejectionLeavesStateUnchanged() throws Exception {
            double ratioBefore = samplerHolder.current().defaultRatio();

            invokeApply(null); // decode failure

            assertThat(samplerHolder.current().defaultRatio()).isEqualTo(ratioBefore);
        }
    }

    // =========================================================================
    // Tests: DOMAIN_REJECTED
    // =========================================================================

    @Nested
    class DomainRejected {

        @Test
        void outOfRangeRatioIsDomainRejected() throws Exception {
            CompositeData payload = buildPayload(
                    new String[]{TracingControlProtocolKeys.SAMPLING_RATIO},
                    new Object[]{1.5d});

            String result = invokeApply(payload);

            assertThat(result).startsWith("DOMAIN_REJECTED");
            assertThat(counter.sum()).isEqualTo(1);
        }

        @Test
        void unknownValidationModeIsDomainRejected() throws Exception {
            CompositeData payload = buildPayload(
                    new String[]{TracingControlProtocolKeys.VALIDATION_MODE},
                    new Object[]{"EXPLODE"});

            String result = invokeApply(payload);

            assertThat(result).startsWith("DOMAIN_REJECTED");
            assertThat(result).contains("violations=");
        }

        @Test
        void conflictingModeAndStrictIsDomainRejected() throws Exception {
            CompositeData payload = buildPayload(
                    new String[]{
                            TracingControlProtocolKeys.VALIDATION_MODE,
                            TracingControlProtocolKeys.VALIDATION_STRICT},
                    new Object[]{"LOG_ONLY", true});

            String result = invokeApply(payload);

            assertThat(result).startsWith("DOMAIN_REJECTED");
        }

        @Test
        void domainRejectionLeavesStateUnchanged() throws Exception {
            boolean enabledBefore = validatingProcessor.isEnabled();
            double ratioBefore    = samplerHolder.current().defaultRatio();

            CompositeData payload = buildPayload(
                    new String[]{
                            TracingControlProtocolKeys.SAMPLING_RATIO,
                            TracingControlProtocolKeys.VALIDATION_ENABLED},
                    new Object[]{-5.0d, true});

            invokeApply(payload);

            assertThat(samplerHolder.current().defaultRatio()).isEqualTo(ratioBefore);
            assertThat(validatingProcessor.isEnabled()).isEqualTo(enabledBefore);
        }
    }

    // =========================================================================
    // Tests: readAppliedState round-trip
    // =========================================================================

    @Nested
    class ReadAppliedState {

        @Test
        void readReturnsNonNullSnapshot() throws Exception {
            CompositeData state = invokeRead();
            assertThat(state).isNotNull();
        }

        @Test
        void readReflectsInitialSamplingRatio() throws Exception {
            CompositeData state = invokeRead();
            String ratio = (String) state.get(TracingControlProtocolKeys.SAMPLING_RATIO);
            assertThat(ratio).isEqualTo("0.1");
        }

        @Test
        void readReflectsAppliedChanges() throws Exception {
            // Apply a new ratio
            CompositeData payload = buildPayload(
                    new String[]{TracingControlProtocolKeys.SAMPLING_RATIO},
                    new Object[]{0.33d});
            invokeApply(payload);

            CompositeData state = invokeRead();
            String ratio = (String) state.get(TracingControlProtocolKeys.SAMPLING_RATIO);
            assertThat(ratio).isEqualTo("0.33");
        }

        @Test
        void readDoesNotMutateState() throws Exception {
            double ratioBefore = samplerHolder.current().defaultRatio();
            long versionBefore = samplerHolder.current().version();

            invokeRead();
            invokeRead(); // second call to verify idempotency

            assertThat(samplerHolder.current().defaultRatio()).isEqualTo(ratioBefore);
            assertThat(samplerHolder.current().version()).isEqualTo(versionBefore);
        }

        @Test
        void readContainsMetadataKeys() throws Exception {
            CompositeData state = invokeRead();
            assertThat(state.getCompositeType().keySet())
                    .contains(
                            "_meta.samplingConfigVersion",
                            "_meta.samplingConfigSource",
                            "_meta.validationConfigVersion",
                            "_meta.validationConfigSource");
        }
    }

    // =========================================================================
    // Tests: counter behaviour
    // =========================================================================

    @Test
    void successDoesNotIncrementCounter() throws Exception {
        CompositeData payload = buildPayload(
                new String[]{TracingControlProtocolKeys.SAMPLING_RATIO},
                new Object[]{0.5d});
        invokeApply(payload);
        assertThat(counter.sum()).isZero();
    }

    @Test
    void multipleFailuresAccumulateInCounter() throws Exception {
        invokeApply(null);          // decode-rejected: +1
        invokeApply(null);          // decode-rejected: +1
        CompositeData badRatio = buildPayload(
                new String[]{TracingControlProtocolKeys.SAMPLING_RATIO},
                new Object[]{99.0d});
        invokeApply(badRatio);      // domain-rejected: +1

        assertThat(counter.sum()).isEqualTo(3);
    }
}
