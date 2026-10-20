package space.br1440.platform.tracing.otel.javaagent.bootstrap.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Field;
import java.util.Map;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.attributes.PlatformSamplingReasons;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionRuntimeEvidence;
import space.br1440.platform.tracing.otel.javaagent.jmx.registration.PlatformTracingMBeanRegistrar;
import space.br1440.platform.tracing.otel.javaagent.sampler.CompositeSampler;
import space.br1440.platform.tracing.otel.javaagent.sampler.SafeSampler;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.assembly.PlatformScrubberFactory;
import space.br1440.platform.tracing.runtime.propagation.control.DefaultInboundTraceControlExtractor;
import space.br1440.platform.tracing.runtime.propagation.control.PlatformTraceContextKeys;
import space.br1440.platform.tracing.test.harness.SamplerHarness;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.autoconfigure.spi.internal.DefaultConfigProperties;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;

class AutoconfigureSessionFactorySamplerTest {

    @Test
    void create_builds_one_safe_sampler_over_the_session_policy_and_evidence() {
        // given
        AutoconfigureSessionFactory factory = factory();
        AgentInstanceId agentInstanceId = AgentInstanceId.random();
        PlatformTracingMBeanRegistrar registrar = spy(new PlatformTracingMBeanRegistrar(agentInstanceId));
        ExtensionRuntimeEvidence evidence = evidence();

        // when
        AutoconfigureSession session = factory.create(
                DefaultConfigProperties.createFromMap(Map.of(
                        "otel.traces.sampler", "always_off",
                        "platform.tracing.sampling.ratio", "0.375",
                        "platform.tracing.javaagent.sampling.drop-paths", "/session-drop",
                        "platform.tracing.javaagent.sampling.force-record-values", "session-force"
                )),
                registrar,
                agentInstanceId,
                evidence
        );
        SafeSampler sampler = session.sampler();
        CompositeSampler core = delegateOf(sampler);

        // then
        assertThat(sampler).isExactlyInstanceOf(SafeSampler.class);
        assertThat(core).isExactlyInstanceOf(CompositeSampler.class);
        assertThat(core.acceptedPolicy()).isSameAs(session.policy());
        assertThat(core.categoryBOverrides().getDroppedRoutes())
                .containsExactlyElementsOf(session.extensionConfig().sampling().dropPaths());
        assertThat(core.categoryBOverrides().getForceRecordValues())
                .containsExactlyInAnyOrderElementsOf(session.extensionConfig().sampling().forceRecordValues());
        assertThat(core.categoryBOverrides().getRouteRatioMap()).isEmpty();
        assertThat(core.categoryBOverrides().getDefaultRatio()).isEqualTo(1.0d);
        assertThat(session.evidence()).isSameAs(evidence);
        verify(registrar).setCompositeSampler(same(core));
    }

    @Test
    void absent_force_record_values_keep_the_default_on_control_value() {
        // given
        AgentInstanceId agentInstanceId = AgentInstanceId.random();
        AutoconfigureSession session = factory().create(
                DefaultConfigProperties.createFromMap(Map.of(
                        "otel.traces.sampler", "always_off",
                        "platform.tracing.sampling.ratio", "0"
                )),
                new PlatformTracingMBeanRegistrar(agentInstanceId),
                agentInstanceId,
                evidence()
        );
        Context forceContext = Context.root().with(
                PlatformTraceContextKeys.TRACE_CONTROL,
                new DefaultInboundTraceControlExtractor().fromHeaders("on", null, null)
        );

        // when
        SafeSampler sampler = session.sampler();
        SamplingResult result = SamplerHarness.of(sampler)
                .traceId("00000000000000000000000000000001")
                .spanKind(SpanKind.SERVER)
                .parentContext(forceContext)
                .sample("GET")
                .result();

        // then
        assertThat(result.getDecision()).isEqualTo(SamplingDecision.RECORD_AND_SAMPLE);
        assertThat(result.getAttributes().get(AttributeKey.stringKey("platform.sampling.reason")))
                .isEqualTo(PlatformSamplingReasons.FORCE_HEADER);
    }

    @Test
    void rejected_session_does_not_publish_the_sampler_to_the_registrar() {
        // given
        PlatformTracingMBeanRegistrar registrar = spy(new PlatformTracingMBeanRegistrar(AgentInstanceId.random()));
        AgentInstanceId foreignAgentInstanceId = AgentInstanceId.random();
        AutoconfigureSessionFactory factory = factory();

        // when/then
        assertThatThrownBy(() -> factory.create(
                DefaultConfigProperties.createFromMap(Map.of("otel.traces.sampler", "always_off")),
                registrar,
                foreignAgentInstanceId,
                evidence()
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Autoconfigure session and JMX registrar agentInstanceId values must match");
        verify(registrar, never()).setCompositeSampler(any());
    }

    private static AutoconfigureSessionFactory factory() {
        return new AutoconfigureSessionFactory(
                new PlatformScrubberFactory(),
                AutoconfigureSessionFactory::currentProcessRemoteJmxAssessor
        );
    }

    private static CompositeSampler delegateOf(SafeSampler sampler) {
        try {
            Field delegate = SafeSampler.class.getDeclaredField("delegate");
            delegate.setAccessible(true);
            return (CompositeSampler) delegate.get(sampler);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("SafeSampler delegate is not inspectable", failure);
        }
    }

    private static ExtensionRuntimeEvidence evidence() {
        return new ExtensionRuntimeEvidence(
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "2.32.0",
                "test-extension",
                "extension-loader",
                "sdk-loader"
        );
    }
}
