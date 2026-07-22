package space.br1440.platform.tracing.e2e.wire;

import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocol;
import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocolKeys;
import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocolOperation;
import space.br1440.platform.tracing.core.control.protocol.RuntimeControlMutationPolicy;
import space.br1440.platform.tracing.core.control.protocol.RuntimePolicyControlHandler;
import space.br1440.platform.tracing.otel.javaagent.control.JmxRuntimePolicyApplier;
import space.br1440.platform.tracing.otel.javaagent.control.ReadAppliedStateHandler;
import space.br1440.platform.tracing.otel.javaagent.jmx.control.PlatformControlProtocolMBean;
import space.br1440.platform.tracing.otel.javaagent.jmx.sampling.PlatformSamplingControl;
import space.br1440.platform.tracing.otel.javaagent.jmx.validation.PlatformValidationControl;
import space.br1440.platform.tracing.otel.javaagent.processor.ValidatingSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.sampler.SamplerStateHolder;

import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Проверяет policy-gated JMX wire path с production handler и applier.
 */
class RuntimeControlMutationWireRoundTripE2ETest {

    @Test
    void disabledMutationRejectsApplyAndPreservesAppliedSnapshot() throws Exception {
        RuntimeFixture fixture = RuntimeFixture.create(false);
        long versionBefore = fixture.samplerHolder.current().version();
        String sourceBefore = fixture.samplerHolder.current().source();

        String result = fixture.mbean.applyPolicy(applyPayload(0.55d));

        assertThat(result).startsWith("MUTATION_REJECTED APPLY_RUNTIME_POLICY");
        assertThat(fixture.samplerHolder.current().defaultRatio()).isEqualTo(0.1d);
        assertThat(fixture.samplerHolder.current().version()).isEqualTo(versionBefore);
        assertThat(fixture.samplerHolder.current().source()).isEqualTo(sourceBefore);
    }

    @Test
    void enabledMutationAppliesWirePayload() throws Exception {
        RuntimeFixture fixture = RuntimeFixture.create(true);

        String result = fixture.mbean.applyPolicy(applyPayload(0.55d));

        assertThat(result).startsWith("SUCCESS APPLY_RUNTIME_POLICY");
        assertThat(fixture.samplerHolder.current().defaultRatio()).isEqualTo(0.55d);
    }

    @Test
    void readAppliedStateRemainsAvailableWhenMutationIsDisabled() throws Exception {
        RuntimeFixture fixture = RuntimeFixture.create(false);

        CompositeData state = fixture.mbean.readAppliedState();

        assertThat(state.get(TracingControlProtocolKeys.SAMPLING_RATIO)).isEqualTo("0.1");
    }

    private static CompositeData applyPayload(double ratio) throws Exception {
        String[] keys = {
                TracingControlProtocolKeys.CONTRACT_VERSION,
                TracingControlProtocolKeys.OPERATION,
                TracingControlProtocolKeys.SAMPLING_RATIO};
        OpenType<?>[] types = {SimpleType.INTEGER, SimpleType.STRING, SimpleType.DOUBLE};
        Object[] values = {1, TracingControlProtocolOperation.APPLY_RUNTIME_POLICY.wireValue(), ratio};
        CompositeType type = new CompositeType("RuntimeControl", "runtime control payload",
                keys, keys, types);
        return new CompositeDataSupport(type, keys, values);
    }

    private record RuntimeFixture(SamplerStateHolder samplerHolder,
                                  PlatformControlProtocolMBean mbean) {

        static RuntimeFixture create(boolean mutationEnabled) {
            SamplerStateHolder samplerHolder = new SamplerStateHolder(
                    true, List.of(), List.of(), Collections.emptyMap(), 0.1d);
            ValidatingSpanProcessor validating = new ValidatingSpanProcessor(false, true);
            LongAdder invalidCounter = new LongAdder();
            JmxRuntimePolicyApplier applier = new JmxRuntimePolicyApplier(
                    new PlatformSamplingControl(samplerHolder, null, invalidCounter),
                    new PlatformValidationControl(validating, invalidCounter));
            RuntimePolicyControlHandler handler = new RuntimePolicyControlHandler(
                    applier, RuntimeControlMutationPolicy.startupConfigured(mutationEnabled));
            PlatformControlProtocolMBean mbean = new PlatformControlProtocolMBean(
                    TracingControlProtocol.current(), handler,
                    new ReadAppliedStateHandler(samplerHolder, validating), invalidCounter);
            return new RuntimeFixture(samplerHolder, mbean);
        }
    }
}
