package space.br1440.platform.tracing.otel.javaagent.scrubbing.engine;

import java.util.Comparator;
import java.util.List;

import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;
import space.br1440.platform.tracing.otel.javaagent.logging.event.ScrubbingLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogField;

import lombok.experimental.UtilityClass;

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
@UtilityClass
public final class PriorityHardening {

    private static final PlatformAgentLogger log = PlatformAgentLogger.get(PriorityHardening.class);

    public static final int CUSTOM_PRIORITY_FLOOR = 900;

    private static final Comparator<ExecutableRule> BY_PRIORITY_THEN_CLASS_NAME =
            Comparator.comparingInt(ExecutableRule::getEffectivePriority)
                    .thenComparing(wrapper -> wrapper.getRuleClass().getName());

    public static List<RuntimeRule> sortAndClampRuntimeRules(List<RuntimeRule> rules) {
        return rules.stream()
                .map(PriorityHardening::clampIfNeeded)
                .sorted(Comparator.comparing(RuntimeRule::executable, BY_PRIORITY_THEN_CLASS_NAME))
                .toList();
    }

    private static RuntimeRule clampIfNeeded(RuntimeRule runtimeRule) {
        ExecutableRule wrapper = runtimeRule.executable();
        if (runtimeRule.origin() != RuleOrigin.BUILT_IN
                && wrapper.getEffectivePriority() < CUSTOM_PRIORITY_FLOOR) {
            log.log(ScrubbingLogEvent.SCRUBBING_PRIORITY_FLOOR_APPLIED,fields -> fields
                    .putString(AgentLogField.NAME, wrapper.getRuleClass().getName())
            );

            return new RuntimeRule(
                    wrapper.withEffectivePriority(CUSTOM_PRIORITY_FLOOR),
                    runtimeRule.origin()
            );
        }

        return runtimeRule;
    }
}
