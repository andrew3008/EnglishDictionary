package space.br1440.platform.tracing.autoconfigure.diagnostics.health.startup;

import org.springframework.boot.actuate.autoconfigure.health.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import space.br1440.platform.tracing.autoconfigure.TracingRuntimeAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.agent.AgentRuntimeState;
import space.br1440.platform.tracing.autoconfigure.agent.TracingDeploymentDiagnostics;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;
import space.br1440.platform.tracing.otel.runtime.state.TracingRuntimeMode;

@AutoConfiguration
@AutoConfigureAfter(TracingRuntimeAutoConfiguration.class)
@ConditionalOnClass(HealthIndicator.class)
@ConditionalOnBean({TracingRuntime.class, TracingDeploymentDiagnostics.class})
public class TracingStartupContractHealthAutoConfiguration {

    /**
     * {@code ConditionalOnEnabledHealthIndicator} резолвит property через
     * {@code Environment.getProperty(prefix + value + ".enabled")} буквальным сравнением строк, а не
     * через relaxed {@code Binder}: {@code ConfigurationPropertyName} не принимает camelCase.
     * Значение задано в kebab-case, поэтому управляющая property
     * {@code management.health.tracing-startup-contract.enabled} резолвится корректно; camelCase
     * значение (как у bean name) сделало бы property молча неработающей — condition вернул бы default
     * {@code true} независимо от настройки.
     */
    private static final String HEALTH_INDICATOR_ENABLED_PROPERTY_ID = "tracing-startup-contract";

    @Bean(name = "tracingStartupContractHealthIndicator")
    @ConditionalOnMissingBean(name = "tracingStartupContractHealthIndicator")
    @ConditionalOnEnabledHealthIndicator(HEALTH_INDICATOR_ENABLED_PROPERTY_ID)
    public HealthIndicator tracingStartupContractHealthIndicator(TracingRuntime tracingRuntime,
                                                                 TracingDeploymentDiagnostics diagnostics) {
        return new TracingStartupContractIndicator(toSnapshot(tracingRuntime, diagnostics));
    }

    private static TracingStartupContractSnapshot toSnapshot(TracingRuntime tracingRuntime,
                                                             TracingDeploymentDiagnostics diagnostics) {
        TracingRuntimeMode mode = tracingRuntime.state().mode();
        AgentRuntimeState agentState = diagnostics.runtimeState();
        String failureCode = diagnostics.agentReadiness().failureCode();

        return new TracingStartupContractSnapshot(
                diagnostics.tracingEnabled(),
                mode.name(),
                agentState.name(),
                failureCode,
                outcomeFor(mode, agentState));
    }

    private static TracingStartupContractOutcome outcomeFor(TracingRuntimeMode mode, AgentRuntimeState agentState) {
        if (mode == TracingRuntimeMode.ENABLED && agentState == AgentRuntimeState.AGENT_READY) {
            return TracingStartupContractOutcome.ENABLED_CONTRACT_SATISFIED;
        }

        if (mode == TracingRuntimeMode.DISABLED_BY_CONFIGURATION && agentState == AgentRuntimeState.DISABLED) {
            return TracingStartupContractOutcome.DISABLED_CONTRACT_SATISFIED;
        }

        return TracingStartupContractOutcome.STARTUP_CONTRACT_VIOLATED;
    }
}
