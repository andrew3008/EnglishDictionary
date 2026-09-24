package space.br1440.platform.tracing.otel.javaagent.scrubbing.assembly;

import java.util.List;

import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogLevel;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentEventCode;
import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;
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
                            int openBreakers,
                            List<ExtensionRuleLoader.FailedEntry> failedEntries) {
        log.emit(AgentLogLevel.INFO, AgentEventCode.SCRUB_INITIALIZED, "Scrubbing rule initialized", null,
                "builtInRules", String.valueOf(builtInRules),
                "bundledSpiRules", String.valueOf(bundledSpiRules),
                "customRules", String.valueOf(customRules),
                "kind", loadingMode,
                "failedProviders", String.valueOf(failedProviders),
                "clampedPriorities", String.valueOf(clampedPriorities),
                "openBreakers", String.valueOf(openBreakers)
        );

        for (ExtensionRuleLoader.FailedEntry entry : failedEntries) {
            log.warn(AgentEventCode.SCRUB_PROVIDER_FAILED, "Scrubbing provider failed", "name", entry.name(),
                    "reason", String.valueOf(entry.reason()));
        }
    }
}
