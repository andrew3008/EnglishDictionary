package space.br1440.platform.tracing.otel.javaagent.scrubbing.engine;

import java.util.Comparator;
import java.util.List;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

/**
 * Валидация приоритетов и детерминированный порядок исполнения правил.
 * <p>
 * Диапазоны приоритетов:
 * <ul>
 *   <li>{@code 0–99}    — critical built-in платформы;</li>
 *   <li>{@code 100–499} — standard built-in платформы;</li>
 *   <li>{@code 500–899} — доменные правила;</li>
 *   <li>{@code 900+}    — пользовательские (custom) правила.</li>
 * </ul>
 * Custom-правило с приоритетом ниже {@value #CUSTOM_PRIORITY_FLOOR} принудительно поднимается
 * (clamp) до {@value #CUSTOM_PRIORITY_FLOOR} с WARN-логом — чтобы оно не могло встать выше
 * платформенных правил. Critical-признак определяется <b>только</b> методом
 * {@code SpanAttributeScrubbingRule.critical()}, а не диапазоном приоритета.
 */
@Slf4j
@UtilityClass
public final class PriorityHardening {

    public static final int CUSTOM_PRIORITY_FLOOR = 900;

    private static final Comparator<ExecutableRule> BY_PRIORITY_THEN_CLASS_NAME =
            Comparator.comparingInt(ExecutableRule::getEffectivePriority)
                    .thenComparing(wrapper -> wrapper.getRuleClass().getName());

    public static List<ExecutableRule> sortAndClamp(List<ExecutableRule> wrappers) {
        return wrappers.stream()
                .map(PriorityHardening::clampIfNeeded)
                .sorted(BY_PRIORITY_THEN_CLASS_NAME)
                .toList();
    }

    public static List<RuntimeRule> sortAndClampRuntimeRules(List<RuntimeRule> rules) {
        return rules.stream()
                .map(r -> new RuntimeRule(clampIfNeeded(r.executable()), r.origin()))
                .sorted(Comparator.comparing(RuntimeRule::executable, BY_PRIORITY_THEN_CLASS_NAME))
                .toList();
    }

    private static ExecutableRule clampIfNeeded(ExecutableRule wrapper) {
        if (!wrapper.isCritical() && wrapper.getEffectivePriority() < CUSTOM_PRIORITY_FLOOR) {
            log.warn("[scrubbing] Custom rule '{}' has priority {} below the allowed floor {}. "
                            + "Priority clamped to {}.",
                    wrapper.getRuleClass().getName(),
                    wrapper.getEffectivePriority(),
                    CUSTOM_PRIORITY_FLOOR,
                    CUSTOM_PRIORITY_FLOOR
            );

            return wrapper.withEffectivePriority(CUSTOM_PRIORITY_FLOOR);
        }

        return wrapper;
    }
}
