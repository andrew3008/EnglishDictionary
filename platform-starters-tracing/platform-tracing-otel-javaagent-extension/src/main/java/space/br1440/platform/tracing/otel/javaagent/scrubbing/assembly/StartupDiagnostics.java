package space.br1440.platform.tracing.otel.javaagent.scrubbing.assembly;

import java.util.List;

import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;
import space.br1440.platform.tracing.otel.javaagent.logging.event.ScrubbingLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogField;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.loader.ExtensionRuleLoader;

import lombok.experimental.UtilityClass;

@UtilityClass
final class StartupDiagnostics {

    private static final PlatformAgentLogger log = PlatformAgentLogger.get(StartupDiagnostics.class);

    public static void emit(int builtInRules,
                            int bundledSpiRules,
                            int customRules,
                            String loadingMode,
                            int failedProviders,
                            int clampedPriorities,
                            List<ExtensionRuleLoader.FailedEntry> failedEntries) {
        log.log(ScrubbingLogEvent.SCRUBBING_RULES_INITIALIZED, fields -> fields
                .putLong(AgentLogField.BUILT_IN_RULES, builtInRules)
                .putLong(AgentLogField.BUNDLED_SPI_RULES, bundledSpiRules)
                .putLong(AgentLogField.CUSTOM_RULES, customRules)
                .putString(AgentLogField.KIND, loadingMode)
                .putLong(AgentLogField.FAILED_PROVIDERS, failedProviders)
                .putLong(AgentLogField.CLAMPED_PRIORITIES, clampedPriorities));

        for (ExtensionRuleLoader.FailedEntry entry : failedEntries) {
            log.log(ScrubbingLogEvent.SCRUBBING_PROVIDER_FAILED, fields -> fields
                    .putString(AgentLogField.NAME, entry.name())
                    .putString(AgentLogField.REASON, entry.reason().name()));
        }
    }
}
