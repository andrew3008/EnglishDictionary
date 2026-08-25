package space.br1440.platform.tracing.autoconfigure.control.management;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.endpoint.web.annotation.WebEndpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import space.br1440.platform.tracing.autoconfigure.control.authority.TracingConfigurationGuardException;
import space.br1440.platform.tracing.autoconfigure.control.jmx.PrivateJmxControlAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.control.settings.TracingSettingsContractViolationException;
import space.br1440.platform.tracing.autoconfigure.control.settings.delivery.TracingSettingsApplier;
import space.br1440.platform.tracing.autoconfigure.control.settings.read.AcceptedSettingsWithSequence;
import space.br1440.platform.tracing.autoconfigure.control.settings.read.CompleteTracingSettingsReader;

@AutoConfiguration
@AutoConfigureAfter(PrivateJmxControlAutoConfiguration.class)
public class TracingSettingsControlAutoConfiguration {

    @Configuration
    @ConditionalOnClass(WebEndpoint.class)
    @ConditionalOnProperty(name = "platform.tracing.configuration.authority", havingValue = "ACTUATOR")
    @ConditionalOnProperty(name = "platform.environment", havingValue = "LOCAL")
    static class ActuatorAdapterConfiguration {

        @Bean
        @ConditionalOnMissingBean(TracingSettingsMutationService.class)
        public TracingSettingsMutationService springActuatorTracingSettingsAdapter(CompleteTracingSettingsReader snapshotReader,
                                                                               TracingSettingsApplier snapshotClient) {
            return new TracingSettingsMutationService(snapshotReader, snapshotClient);
        }

        @Bean
        @ConditionalOnMissingBean(TracingControlEndpoint.class)
        public TracingControlEndpoint tracingControlEndpoint(TracingSettingsMutationService mutationService) {
            return new TracingControlEndpoint(mutationService);
        }

        @Bean
        public ActuatorAgentReadinessProbe actuatorAgentReadinessProbe(CompleteTracingSettingsReader snapshotReader) {
            return new ActuatorAgentReadinessProbe(snapshotReader);
        }
    }

    static final class ActuatorAgentReadinessProbe implements SmartLifecycle {

        private static final Logger probeLog = LoggerFactory.getLogger(ActuatorAgentReadinessProbe.class);

        private final CompleteTracingSettingsReader reader;
        private volatile boolean running = false;

        ActuatorAgentReadinessProbe(CompleteTracingSettingsReader reader) {
            this.reader = reader;
        }

        @Override
        public void start() {
            try {
                AcceptedSettingsWithSequence accepted = reader.currentComplete()
                        .orElseThrow(() -> new TracingConfigurationGuardException(
                                "[readiness probe] ACTUATOR authority requires a complete accepted "
                                        + "snapshot at startup. currentComplete() returned empty — Agent "
                                        + "is unreachable or has not yet committed an accepted policy. "
                                        + "Application tracing-configuration readiness is NOT achieved."));

                probeLog.info(
                        "[readiness probe] Agent reachable via readAcceptedComplete: "
                                + "sequence={}", accepted.acceptedSequence());

            } catch (TracingSettingsContractViolationException e) {
                throw new TracingSettingsContractViolationException(
                        "[readiness probe] FATAL: readAcceptedComplete not found on Agent MBean, "
                                + "or returned a malformed/incompatible response. PRIV-R07 requires a "
                                + "compatible Agent build. Startup is BLOCKED until a compatible Agent "
                                + "is attached. Original cause: " + e.getMessage(), e);
            }

            running = true;
        }

        @Override
        public void stop() {
            running = false;
        }

        @Override
        public boolean isRunning() {
            return running;
        }
    }
}
