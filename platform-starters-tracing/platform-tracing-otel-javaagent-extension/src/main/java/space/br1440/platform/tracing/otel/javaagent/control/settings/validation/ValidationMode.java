package space.br1440.platform.tracing.otel.javaagent.control.settings.validation;

import space.br1440.platform.common.util.StringUtils;

import lombok.extern.slf4j.Slf4j;

/**
 * Запрошенный или действующий режим валидации для {@code ValidatingSpanProcessor}.
 * <p>
 * Конфигурационное свойство — {@code platform.tracing.validation.mode}, см. {@link
 * space.br1440.platform.tracing.otel.javaagent.configuration.ValidationExtensionConfig}.
 * <p>
 * Допустимые значения для {@code platform.tracing.validation.mode} (без учёта регистра):
 * <ul>
 *   <li>{@code LENIENT} — валидировать и предупреждать о недостающих атрибутах; никогда не бросать исключение</li>
 *   <li>{@code STRICT} — валидировать и выдавать throttled ERROR-диагностику при недостающих
 *       атрибутах без прерывания завершения span; разрешён только в {@link PlatformEnvironment#DEV} и
 *       {@link PlatformEnvironment#STAGING}</li>
 *   <li>{@code DISABLED} — полностью пропускать валидацию; разрешён везде с видимостью риска</li>
 * </ul>
 * Неизвестные или пустые значения по умолчанию сводятся к {@link #LENIENT}.
 */
@Slf4j
public enum ValidationMode {

    LENIENT,
    STRICT,
    DISABLED;

    public boolean isLenient() {
        return (this == LENIENT);
    }

    public boolean isStrict() {
        return (this == STRICT);
    }

    public boolean isDisabled() {
        return (this == DISABLED);
    }

    public static ValidationMode parse(String value) {
        if (StringUtils.isNullOrBlank(value)) {
            return LENIENT;
        }

        return switch (value.trim().toUpperCase()) {
            case "LENIENT" -> LENIENT;
            case "STRICT" -> STRICT;
            case "DISABLED" -> DISABLED;
            default -> {
                log.warn("Unrecognized platform.tracing.validation.mode='{}'; defaulting to LENIENT", value);
                yield LENIENT;
            }
        };
    }
}
