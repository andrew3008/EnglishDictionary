package space.br1440.platform.tracing.autoconfigure.sampling;

import space.br1440.platform.tracing.autoconfigure.TracingProperties;

import java.util.Objects;

/**
 * Spring-side scrubbing runtime config schema v1 (PR-7C).
 * <p>
 * Тонкий view домена scrubbing из {@link TracingProperties.Scrubbing}. Не является
 * вторым authoritative snapshot — agent-side {@code ScrubbingPolicyHolder} остаётся source of truth.
 * <p>
 * Runtime-mutable поля schema v1 (публикуются одним атомарным {@code updateScrubbingPolicy}):
 * <ul>
 *   <li>{@code platform.tracing.scrubbing.enabled}</li>
 *   <li>{@code platform.tracing.scrubbing.built-in-rules}</li>
 * </ul>
 * {@code rulesConfig} и SPI-правила — startup-only, не входят в JMX-домен runtime update.
 * <p>
 * Unknown built-in rule names передаются as-is; agent пропускает неизвестные (PR-7B parity).
 */
public record ScrubbingRuntimeConfig(boolean enabled, String[] ruleNames) {

    /** Источник публикации для Spring reconciliation path (RefreshScope / actuator refresh). */
    public static final String SOURCE = "spring-runtime-config";

    /**
     * Извлекает schema v1 из текущего {@link TracingProperties.Scrubbing} без кэширования.
     */
    public static ScrubbingRuntimeConfig from(TracingProperties.Scrubbing scrubbing) {
        Objects.requireNonNull(scrubbing, "scrubbing");
        ScrubbingRuleNamesWire.WireArray wire = ScrubbingRuleNamesWire.fromList(scrubbing.getBuiltInRules());
        return new ScrubbingRuntimeConfig(scrubbing.isEnabled(), wire.ruleNames());
    }
}
