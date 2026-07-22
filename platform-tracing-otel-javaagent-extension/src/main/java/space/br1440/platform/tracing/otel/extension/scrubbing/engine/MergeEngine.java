package space.br1440.platform.tracing.otel.extension.scrubbing.engine;

import space.br1440.platform.tracing.api.spi.ScrubbingAction;
import space.br1440.platform.tracing.api.spi.ScrubbingDecision;

import java.util.List;

/**
 * Движок «накопить и свести» (accumulate-and-merge), заменяющий стратегию first-match-wins.
 * <p>
 * Старый процессор возвращал первое не-KEEP решение и выходил. Новый движок:
 * <ol>
 *   <li>обходит ВСЕ правила (через {@link RuleExecutionWrapper}, с учётом {@code supports()} и
 *       состояния circuit breaker);</li>
 *   <li>сводит решения по строгости: KEEP &lt; TRUNCATE &lt; HASH &lt; MASK &lt; DROP;</li>
 *   <li>делает ранний выход (early-exit), как только получено терминальное решение от
 *       critical-правила;</li>
 *   <li>терминальные решения от <b>custom</b>-правил игнорирует;</li>
 *   <li>тай-брейкер двух конфликтующих терминальных решений critical built-in: побеждает более
 *       строгое действие, при равной строгости — меньший приоритет (обеспечивается порядком
 *       сортировки в {@link PriorityHardening}).</li>
 * </ol>
 *
 * <h3>«KEEP never weakens»</h3>
 * KEEP от любого правила не может заменить уже накопленное более строгое действие.
 *
 * <h3>Терминальность</h3>
 * Решение считается терминальным, если правило {@link RuleExecutionWrapper#isCritical() critical}
 * и его действие не KEEP. Это запрещает ослаблять решение critical built-in последующими
 * правилами и даёт ранний выход на горячем пути.
 */
public final class MergeEngine {

    private MergeEngine() {
        // utility-класс
    }

    /**
     * Сводит решения всех правил для нормализованного ключа/значения.
     *
     * @param wrappers отсортированный (по возрастанию приоритета) список обёрток правил
     * @param key      нормализованный платформой ключ атрибута
     * @param value    сырое значение атрибута
     * @return итоговое решение; никогда {@code null}
     */
    public static ScrubbingDecision evaluate(List<RuleExecutionWrapper> wrappers, String key, Object value) {
        ScrubbingDecision accumulated = ScrubbingDecision.keep();

        for (RuleExecutionWrapper wrapper : wrappers) {
            ScrubbingDecision candidate = wrapper.execute(key, value);
            if (candidate == null) {
                continue; // правило пропущено (custom в OPEN)
            }

            // Терминальность учитывается только для critical-правил.
            boolean terminal = wrapper.isCritical() && candidate.action() != ScrubbingAction.KEEP;
            accumulated = merge(accumulated, candidate, terminal);

            if (terminal) {
                break; // ранний выход после терминального решения critical built-in
            }
        }
        return accumulated;
    }

    /**
     * Сводит {@code next} в {@code current} по строгости.
     * <p>Порядок строгости: KEEP(0) &lt; TRUNCATE(1) &lt; HASH(2) &lt; MASK(3) &lt; DROP(4).
     */
    static ScrubbingDecision merge(ScrubbingDecision current, ScrubbingDecision next, boolean nextIsTerminal) {
        // KEEP никогда не ослабляет более строгое текущее решение.
        if (next.action() == ScrubbingAction.KEEP) {
            return current;
        }
        int currentStrict = strictness(current.action());
        int nextStrict = strictness(next.action());

        if (nextStrict > currentStrict) {
            return promote(next, nextIsTerminal);
        }
        if (nextStrict == currentStrict && nextIsTerminal && !current.terminal()) {
            return promote(next, true);
        }
        return current;
    }

    /**
     * Помечает решение терминальным, если требуется. Built-in critical-правила могут возвращать
     * нетерминальные решения (например, fail-closed MASK или обычный drop), но в контексте
     * critical-правила движок трактует их как терминальные — отражаем это в самом решении, чтобы
     * последующие правила в merge корректно увидели флаг.
     */
    private static ScrubbingDecision promote(ScrubbingDecision decision, boolean terminal) {
        if (terminal && !decision.terminal()) {
            return new ScrubbingDecision(decision.action(), decision.reason(), decision.maxLength(), true);
        }
        return decision;
    }

    private static int strictness(ScrubbingAction action) {
        return switch (action) {
            case KEEP -> 0;
            case TRUNCATE -> 1;
            case HASH -> 2;
            case MASK -> 3;
            case DROP -> 4;
        };
    }
}
