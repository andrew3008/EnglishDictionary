package space.br1440.platform.tracing.autoconfigure.control.jmx;

import java.lang.management.ManagementFactory;
import javax.management.MBeanServer;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;

import space.br1440.platform.tracing.autoconfigure.TracingRuntimeAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.agent.AgentReadinessCoherenceException;
import space.br1440.platform.tracing.autoconfigure.agent.AgentReadinessCoherenceGuard;
import space.br1440.platform.tracing.autoconfigure.agent.AgentRuntimeState;
import space.br1440.platform.tracing.autoconfigure.agent.TracingDeploymentDiagnostics;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingControlChannelCondition;
import space.br1440.platform.tracing.autoconfigure.control.TracingConfigurationGuardException;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxWireGraphValidator;
import space.br1440.platform.tracing.autoconfigure.status.AcceptedSettingsStatusReader;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@AutoConfiguration
@AutoConfigureAfter(TracingRuntimeAutoConfiguration.class)
@Conditional(TracingControlChannelCondition.OnNone.class)
public class PlatformJmxReadAutoConfiguration {

    @Bean
    JmxAcceptedSettingsReader acceptedTracingSettingsReader(AgentReadinessCoherenceGuard coherenceGuard,
                                                             TracingDeploymentDiagnostics startupDiagnostics) {
        AgentRuntimeState runtimeState = startupDiagnostics.runtimeState();
        if (runtimeState != AgentRuntimeState.AGENT_READY) {
            throw new TracingConfigurationGuardException("""
                    JMX_CLIENT_STARTUP_TRUST_NOT_ESTABLISHED: Controlled Agent startup verification \
                    must reach AGENT_READY before JMX control initialization. runtimeState=%s\
                    """.formatted(runtimeState));
        }

        MBeanServer platformMBeanServer = ManagementFactory.getPlatformMBeanServer();
        JmxSettingsMetadataGate metadataGate = new JmxSettingsMetadataGate(platformMBeanServer, coherenceGuard);
        JmxAcceptedSettingsReader reader = new JmxAcceptedSettingsReader(
                platformMBeanServer,
                coherenceGuard,
                new JmxWireGraphValidator()
        );

        try {
            metadataGate.verifyStatusMetadata();
        } catch (AgentReadinessCoherenceException coherenceFailure) {
            coherenceGuard.recordTrustFailure(coherenceFailure.failureCode());
            throw new TracingConfigurationGuardException(
                    coherenceFailure.failureCode() + ": Platform tracing MBean metadata was read outside the pinned readiness generation",
                    coherenceFailure
            );
        } catch (Exception unavailableMetadata) {
            reader.markMetadataUnavailable(unavailableMetadata);
            log.warn(
                    "Accepted settings metadata is unavailable; tracing status will report SETTINGS_UNREADABLE. errorType={}",
                    unavailableMetadata.getClass().getName()
            );
        }

        return reader;
    }

    @Bean
    AcceptedSettingsStatusReader acceptedSettingsStatusReader(JmxAcceptedSettingsReader reader) {
        return reader::currentForStatusSnapshot;
    }
}
