package space.br1440.platform.tracing.otel.extension.scrubbing.diagnostics;

import lombok.extern.slf4j.Slf4j;
import space.br1440.platform.tracing.otel.extension.scrubbing.loader.ExtensionRuleLoader;

import java.util.List;

/**
 * Сводная диагностика старта pipeline scrubbing'а (PR-6).
 * <p>
 * Выводит один INFO-лог с агрегированным состоянием и по одному WARN на каждого неуспешного
 * провайдера с его {@link FailedProviderReason}. Имена/причины предназначены для JMX/Actuator и
 * <b>не</b> используются как метки Prometheus (high-cardinality).
 */
@Slf4j
public final class StartupDiagnostics {

    private StartupDiagnostics() {
        // utility-класс
    }

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
