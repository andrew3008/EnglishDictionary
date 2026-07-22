package space.br1440.platform.tracing.otel.javaagent.scrubbing.engine;

import lombok.extern.slf4j.Slf4j;

import java.util.Comparator;
import java.util.List;

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
 * <p>
 * Сортировка детерминированна: по возрастанию {@code effectivePriority}, затем по имени класса.
 */
@Slf4j
public final class PriorityHardening {

    /** Нижняя граница приоритета для custom-правил. */
    public static final int CUSTOM_PRIORITY_FLOOR = 900;

    private PriorityHardening() {
        // utility-класс
    }

    /**
     * Применяет clamp к custom-правилам и возвращает новый детерминированно отсортированный
     * неизменяемый список.
     */
    public static List<RuleExecutionWrapper> sortAndClamp(List<RuleExecutionWrapper> wrappers) {
        return wrappers.stream()
                .map(PriorityHardening::clampIfNeeded)
                .sorted(Comparator
                        .comparingInt(RuleExecutionWrapper::getEffectivePriority)
                        .thenComparing(w -> w.getRuleClass().getName()))
                .toList();
    }

    private static RuleExecutionWrapper clampIfNeeded(RuleExecutionWrapper wrapper) {
        if (!wrapper.isCritical() && wrapper.getEffectivePriority() < CUSTOM_PRIORITY_FLOOR) {
            log.warn("[scrubbing] Custom-правило '{}' имеет приоритет {} ниже допустимой границы {}. "
                            + "Приоритет поднят (clamp) до {}.",
                    wrapper.getRuleClass().getName(), wrapper.getEffectivePriority(),
                    CUSTOM_PRIORITY_FLOOR, CUSTOM_PRIORITY_FLOOR);
            return wrapper.withEffectivePriority(CUSTOM_PRIORITY_FLOOR);
        }
        return wrapper;
    }
}
