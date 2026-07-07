package space.br1440.platform.tracing.autoconfigure.sampling;

import space.br1440.platform.tracing.autoconfigure.TracingProperties;

import java.util.Objects;

/**
 * Spring-side validation runtime config schema v1 (PR-8C).
 * <p>
 * Тонкий view домена validation из {@link TracingProperties.Validation}. Не является
 * вторым authoritative snapshot — agent-side {@code ValidationPolicyHolder} остаётся source of truth.
 * <p>
 * Runtime-mutable поля schema v1 (публикуются одним атомарным {@code updateValidationPolicy}):
 * <ul>
 *   <li>{@code platform.tracing.validation.enabled}</li>
 *   <li>{@code platform.tracing.validation.strict}</li>
 * </ul>
 */
public record ValidationRuntimeConfig(boolean enabled, boolean strict) {

    /** Источник публикации для Spring reconciliation path (RefreshScope / actuator refresh). */
    public static final String SOURCE = "spring-runtime-config";

    /**
     * Извлекает schema v1 из текущего {@link TracingProperties.Validation} без кэширования.
     */
    public static ValidationRuntimeConfig from(TracingProperties.Validation validation) {
        Objects.requireNonNull(validation, "validation");
        return new ValidationRuntimeConfig(validation.isEnabled(), validation.isStrict());
    }
}
