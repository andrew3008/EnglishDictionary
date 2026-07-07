package space.br1440.platform.tracing.otel.extension.scrubbing.engine;

import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.spi.ScrubbingAction;
import space.br1440.platform.tracing.api.spi.ScrubbingDecision;
import space.br1440.platform.tracing.api.spi.SensitiveDataRule;
import space.br1440.platform.tracing.otel.extension.scrubbing.circuitbreaker.RuleCircuitBreaker;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тесты accumulate-and-merge движка (PR-3): «KEEP never weakens», игнор терминальных решений
 * custom-правил, тай-брейкер двух critical, ранний выход.
 */
class MergeEngineTest {

    private static SensitiveDataRule rule(boolean critical, int priority, ScrubbingDecision result) {
        return new SensitiveDataRule() {
            @Override public String name() { return "r" + priority + critical; }
            @Override public int priority() { return priority; }
            @Override public boolean critical() { return critical; }
            @Override public ScrubbingDecision evaluate(String key, Object value) { return result; }
        };
    }

    private static RuleExecutionWrapper wrap(SensitiveDataRule r) {
        return new RuleExecutionWrapper(r, new RuleCircuitBreaker(r.name()));
    }

    @Test
    void keep_не_ослабляет_mask() {
        var mask = wrap(rule(true, 10, ScrubbingDecision.mask("oauth")));
        var keep = wrap(rule(false, 900, ScrubbingDecision.keep()));

        ScrubbingDecision result = MergeEngine.evaluate(List.of(mask, keep), "authorization", "Bearer x");
        assertThat(result.action()).isEqualTo(ScrubbingAction.MASK);
    }

    @Test
    void critical_drop_не_ослабляется_последующим_hash() {
        var drop = wrap(rule(true, 10, ScrubbingDecision.drop("oauth")));
        var hash = wrap(rule(false, 130, ScrubbingDecision.hash("email")));

        ScrubbingDecision result = MergeEngine.evaluate(List.of(drop, hash), "x-authorization", "token");
        assertThat(result.action()).isEqualTo(ScrubbingAction.DROP);
        assertThat(result.terminal()).isTrue();
    }

    @Test
    void терминальное_решение_custom_правила_не_блокирует_последующее_правило() {
        // Custom-правило вернуло maskTerminal — если бы terminal учитывался, был бы early-exit и
        // MASK заблокировал бы последующее. Но для custom terminal игнорируется → DROP перекрывает.
        var customTerminalMask = wrap(rule(false, 900, ScrubbingDecision.maskTerminal("custom")));
        var laterDrop = wrap(rule(false, 950, ScrubbingDecision.drop("later")));

        ScrubbingDecision result = MergeEngine.evaluate(List.of(customTerminalMask, laterDrop), "some.key", "v");
        assertThat(result.action()).isEqualTo(ScrubbingAction.DROP);
    }

    @Test
    void tie_breaker_drop_бьёт_mask_для_двух_critical() {
        var criticalDrop = wrap(rule(true, 10, ScrubbingDecision.drop("oauth")));
        var criticalMask = wrap(rule(true, 20, ScrubbingDecision.mask("other")));

        ScrubbingDecision result = MergeEngine.evaluate(List.of(criticalDrop, criticalMask), "authorization", "s");
        assertThat(result.action()).isEqualTo(ScrubbingAction.DROP);
    }

    @Test
    void весь_keep_даёт_keep() {
        var a = wrap(rule(false, 900, ScrubbingDecision.keep()));
        var b = wrap(rule(true, 10, ScrubbingDecision.keep()));
        assertThat(MergeEngine.evaluate(List.of(a, b), "k", "v").action()).isEqualTo(ScrubbingAction.KEEP);
    }
}
