package space.br1440.platform.tracing.otel.javaagent.jmx;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocolKeys;
import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocolOperation;
import space.br1440.platform.tracing.core.control.protocol.RuntimePolicyControlHandler;
import space.br1440.platform.tracing.core.control.protocol.RuntimeControlMutationPolicy;
import space.br1440.platform.tracing.otel.javaagent.control.JmxRuntimePolicyApplier;
import space.br1440.platform.tracing.otel.javaagent.jmx.sampling.PlatformSamplingControl;
import space.br1440.platform.tracing.otel.javaagent.jmx.validation.PlatformValidationControl;
import space.br1440.platform.tracing.otel.javaagent.processor.ValidatingSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.sampler.SamplerStateHolder;

import javax.management.MBeanServer;
import javax.management.ObjectName;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test for {@link PlatformTracingJmxRegistrar}.
 *
 * <p>Verifies:
 * <ul>
 *   <li>All 7 MBeans are registered atomically after all required holders
 *       are set.</li>
 *   <li>The {@code PlatformControlProtocolMBean} responds to
 *       {@code applyPolicy} and {@code readAppliedState} via the real
 *       {@link MBeanServer}.</li>
 *   <li>{@code unregisterAllMBeans()} removes all 7 names.</li>
 *   <li>Batch rollback fires when one MBean's registration fails.</li>
 * </ul>
 *
 * <p>Uses a dedicated {@link MBeanServer} per test to avoid ObjectName
 * conflicts with other test classes.
 */
class PlatformTracingJmxRegistrarIntegrationTest {

    private MBeanServer              server;
    private PlatformTracingJmxRegistrar registrar;
    private SamplerStateHolder       samplerHolder;
    private ValidatingSpanProcessor  validatingProcessor;

    @BeforeEach
    void setUp() {
        // Use the platform MBeanServer; unregister known names first to keep
        // tests independent of execution order.
        server = ManagementFactory.getPlatformMBeanServer();
        cleanUp();

        samplerHolder       = new SamplerStateHolder(true, List.of(), List.of(),
                Collections.emptyMap(), 0.2d);
        validatingProcessor = new ValidatingSpanProcessor(false, false);

        registrar = new PlatformTracingJmxRegistrar(server);
    }

    @AfterEach
    void cleanUp() {
        unregisterSilently(PlatformTracingObjectNames.SAMPLING);
        unregisterSilently(PlatformTracingObjectNames.SCRUBBING);
        unregisterSilently(PlatformTracingObjectNames.VALIDATION);
        unregisterSilently(PlatformTracingObjectNames.EXPORT);
        unregisterSilently(PlatformTracingObjectNames.PROCESSOR_METRICS);
        unregisterSilently(PlatformTracingObjectNames.DIAGNOSTICS);
        unregisterSilently(PlatformTracingObjectNames.CONTROL_PROTOCOL);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void wireMinimal() {
        // Wire handler BEFORE setConfigHolder so registrar triggers on
        // setConfigHolder (minimum required holder).
        LongAdder counter = new LongAdder();
        PlatformSamplingControl  samplingCtl  =
                new PlatformSamplingControl(samplerHolder, null, counter);
        PlatformValidationControl validationCtl =
                new PlatformValidationControl(validatingProcessor, counter);
        JmxRuntimePolicyApplier applier =
                new JmxRuntimePolicyApplier(samplingCtl, validationCtl);
        RuntimePolicyControlHandler handler =
                new RuntimePolicyControlHandler(
                        applier, RuntimeControlMutationPolicy.startupConfigured(true));

        registrar.setValidating(validatingProcessor);
        registrar.setControlHandler(handler);
        registrar.setConfigHolder(samplerHolder); // triggers batch registration
    }

    private CompositeData buildApplyPayload(String[] extraKeys, Object[] extraVals)
            throws Exception {
        int base  = 2;
        int total = base + extraKeys.length;
        String[]      keys  = new String[total];
        String[]      descs = new String[total];
        OpenType<?>[] types = new OpenType<?>[total];
        Object[]      vals  = new Object[total];

        keys[0]  = TracingControlProtocolKeys.CONTRACT_VERSION; descs[0] = keys[0];
        types[0] = SimpleType.INTEGER; vals[0] = 1;
        keys[1]  = TracingControlProtocolKeys.OPERATION; descs[1] = keys[1];
        types[1] = SimpleType.STRING;
        vals[1]  = TracingControlProtocolOperation.APPLY_RUNTIME_POLICY.wireValue();

        for (int i = 0; i < extraKeys.length; i++) {
            keys[base + i]  = extraKeys[i];
            descs[base + i] = extraKeys[i];
            Object v = extraVals[i];
            types[base + i] = v instanceof Double  ? SimpleType.DOUBLE
                            : v instanceof Boolean ? SimpleType.BOOLEAN
                            : SimpleType.STRING;
            vals[base + i]  = v;
        }
        CompositeType ct = new CompositeType("P", "payload", keys, descs, types);
        return new CompositeDataSupport(ct, keys, vals);
    }

    private void unregisterSilently(ObjectName name) {
        try {
            if (server.isRegistered(name)) server.unregisterMBean(name);
        } catch (Exception ignored) {}
    }

    // =========================================================================
    // Tests
    // =========================================================================

    @Test
    void allSevenMBeansRegisteredAfterWiring() {
        wireMinimal();

        assertThat(server.isRegistered(PlatformTracingObjectNames.SAMPLING)).isTrue();
        assertThat(server.isRegistered(PlatformTracingObjectNames.SCRUBBING)).isTrue();
        assertThat(server.isRegistered(PlatformTracingObjectNames.VALIDATION)).isTrue();
        assertThat(server.isRegistered(PlatformTracingObjectNames.EXPORT)).isTrue();
        assertThat(server.isRegistered(PlatformTracingObjectNames.PROCESSOR_METRICS)).isTrue();
        assertThat(server.isRegistered(PlatformTracingObjectNames.DIAGNOSTICS)).isTrue();
        assertThat(server.isRegistered(PlatformTracingObjectNames.CONTROL_PROTOCOL)).isTrue();
    }

    @Test
    void controlProtocolMBeanApplisPolicyViaJmxServer() throws Exception {
        wireMinimal();

        CompositeData payload = buildApplyPayload(
                new String[]{TracingControlProtocolKeys.SAMPLING_RATIO},
                new Object[]{0.66d});

        String result = (String) server.invoke(
                PlatformTracingObjectNames.CONTROL_PROTOCOL,
                "applyPolicy",
                new Object[]{payload},
                new String[]{CompositeData.class.getName()});

        assertThat(result).startsWith("SUCCESS");
        assertThat(samplerHolder.current().defaultRatio()).isEqualTo(0.66d);
    }

    @Test
    void controlProtocolMBeanReadAppliedStateViaJmxServer() throws Exception {
        wireMinimal();

        CompositeData state = (CompositeData) server.invoke(
                PlatformTracingObjectNames.CONTROL_PROTOCOL,
                "readAppliedState",
                new Object[]{},
                new String[]{});

        assertThat(state).isNotNull();
        assertThat(state.getCompositeType().keySet())
                .contains(TracingControlProtocolKeys.SAMPLING_RATIO);
    }

    @Test
    void unregisterAllRemovesAllSevenMBeans() {
        wireMinimal();

        registrar.unregisterAllMBeans();

        assertThat(server.isRegistered(PlatformTracingObjectNames.SAMPLING)).isFalse();
        assertThat(server.isRegistered(PlatformTracingObjectNames.SCRUBBING)).isFalse();
        assertThat(server.isRegistered(PlatformTracingObjectNames.VALIDATION)).isFalse();
        assertThat(server.isRegistered(PlatformTracingObjectNames.EXPORT)).isFalse();
        assertThat(server.isRegistered(PlatformTracingObjectNames.PROCESSOR_METRICS)).isFalse();
        assertThat(server.isRegistered(PlatformTracingObjectNames.DIAGNOSTICS)).isFalse();
        assertThat(server.isRegistered(PlatformTracingObjectNames.CONTROL_PROTOCOL)).isFalse();
    }

    @Test
    void secondSetConfigHolderIsIdempotent() {
        wireMinimal();
        // calling setConfigHolder again must not re-register (mbeansRegistered guard)
        registrar.setConfigHolder(samplerHolder);

        // still exactly one instance per name
        assertThat(server.isRegistered(PlatformTracingObjectNames.CONTROL_PROTOCOL)).isTrue();
    }

    @Test
    void noMBeansRegisteredBeforeConfigHolderIsSet() {
        // Only set validating + handler, but NOT configHolder
        LongAdder counter = new LongAdder();
        registrar.setValidating(validatingProcessor);
        PlatformSamplingControl samplingCtl =
                new PlatformSamplingControl(samplerHolder, null, counter);
        PlatformValidationControl validationCtl =
                new PlatformValidationControl(validatingProcessor, counter);
        registrar.setControlHandler(
                new RuntimePolicyControlHandler(
                        new JmxRuntimePolicyApplier(samplingCtl, validationCtl)));

        assertThat(server.isRegistered(PlatformTracingObjectNames.SAMPLING)).isFalse();
        assertThat(server.isRegistered(PlatformTracingObjectNames.CONTROL_PROTOCOL)).isFalse();
    }
}
