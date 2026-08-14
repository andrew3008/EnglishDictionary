package space.br1440.platform.tracing.otel.javaagent.scrubbing.diagnostics;

import java.util.List;

import space.br1440.platform.tracing.otel.javaagent.scrubbing.loader.ExtensionRuleLoader;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@UtilityClass
public final class StartupDiagnostics {

    public static void emit(int builtInRules,
                            int bundledSpiRules,
                            int customRules,
                            String loadingMode,
                            int failedProviders,
                            int clampedPriorities,
                            int openBreakers,
                            List<ExtensionRuleLoader.FailedEntry> failedEntries) {
        log.info("[scrubbing] Инициализировано — builtInRules={}, bundledSpiRules={}, customRules={}, "
                        + "loadingMode={}, failedProviders={}, clampedPriorities={}, openBreakers={}",
                builtInRules, bundledSpiRules, customRules,
                loadingMode, failedProviders, clampedPriorities, openBreakers);

        for (ExtensionRuleLoader.FailedEntry entry : failedEntries) {
            log.warn("[scrubbing] Неуспешный провайдер: name='{}', reason={}", entry.name(), entry.reason());
        }
    }
}
