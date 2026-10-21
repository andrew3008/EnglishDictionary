package space.br1440.platform.tracing.otel.javaagent.bootstrap.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionConfig;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSourceTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionRuntimeEvidence;
import space.br1440.platform.tracing.otel.javaagent.jmx.registration.PlatformTracingMBeanRegistrar;
import space.br1440.platform.tracing.otel.javaagent.jmx.security.RemoteJmxMutationRegistrationAssessor;
import space.br1440.platform.tracing.otel.javaagent.sampler.SafeSampler;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntime;

class AutoconfigureSessionIdentityTest {

    @Test
    void constructorRejectsRegistrarFromDifferentAgentInstance() {
        // given
        TracingSettingsSource policy = TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random());
        AgentInstanceId agentInstanceId = policy.current().agentInstanceId();
        PlatformTracingMBeanRegistrar registrar =
                new PlatformTracingMBeanRegistrar(AgentInstanceId.random());

        // when
        Throwable failure = catchThrowable(() -> session(agentInstanceId, policy, registrar));

        // then
        assertThat(failure)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Autoconfigure session and JMX registrar agentInstanceId values must match");
    }

    @Test
    void constructorRejectsSettingsFromDifferentAgentInstance() {
        // given
        AgentInstanceId agentInstanceId = AgentInstanceId.random();
        TracingSettingsSource policy = TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random());
        PlatformTracingMBeanRegistrar registrar = new PlatformTracingMBeanRegistrar(agentInstanceId);

        // when
        Throwable failure = catchThrowable(() -> session(agentInstanceId, policy, registrar));

        // then
        assertThat(failure)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Autoconfigure session and tracing settings agentInstanceId values must match");
    }

    private static void session(AgentInstanceId agentInstanceId,
                                TracingSettingsSource policy,
                                PlatformTracingMBeanRegistrar registrar) {
        new AutoconfigureSession(
                agentInstanceId,
                mock(ExtensionConfig.class),
                policy,
                mock(SafeSampler.class),
                mock(ExtensionRuntimeEvidence.class),
                registrar,
                mock(ScrubbingRuntime.class),
                mock(RemoteJmxMutationRegistrationAssessor.class)
        );
    }
}
