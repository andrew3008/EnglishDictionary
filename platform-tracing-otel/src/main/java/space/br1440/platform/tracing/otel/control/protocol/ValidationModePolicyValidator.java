package space.br1440.platform.tracing.otel.control.protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocolKeys;

import lombok.experimental.UtilityClass;

/**
 * Доменный валидатор полей режима валидации в нормализованном payload control-protocol.
 *
 * <p>Структурная валидация (тип, неизвестные ключи) уже выполняется
 * {@code TracingControlProtocolDecoder} на уровне API. Этот валидатор
 * применяет два <em>доменных / policy</em>-правила, для которых нужна
 * семантика {@code ValidationSnapshot}:
 *
 * <ol>
 *   <li><b>Правило допустимых значений</b> — если {@code validation.mode}
 *       присутствует, его значение должно быть одним из известных токенов
 *       режима ({@code LOG_ONLY} или {@code STRICT}). Любая другая строка
 *       отклоняется как неизвестный режим валидации.</li>
 *   <li><b>Правило согласованности полей</b> — если в одном payload
 *       одновременно заданы {@code validation.mode} и {@code validation.strict},
 *       они должны согласовываться:
 *       {@code LOG_ONLY} подразумевает {@code strict=false},
 *       {@code STRICT} подразумевает {@code strict=true}.
 *       Расхождение отклоняется как конфликт политики: применение такого
 *       payload привело бы к неоднозначному состоянию
 *       {@code ValidationSnapshot}.</li>
 * </ol>
 *
 * <p>
 * Включённость валидации как таковой выражается отдельным boolean-ключом
 * {@code validation.enabled} и здесь не проверяется.
 */
@UtilityClass
public final class ValidationModePolicyValidator {

    public static final String MODE_LOG_ONLY = "LOG_ONLY";
    public static final String MODE_STRICT = "STRICT";

    private static final Set<String> ALLOWED_MODES = Set.of(MODE_LOG_ONLY, MODE_STRICT);

    public static TracingControlDomainValidationResult validate(Map<String, Object> normalizedPayload) {
        List<String> violations = new ArrayList<>();

        String mode = modeValue(normalizedPayload);
        Boolean strict = strictValue(normalizedPayload);

        validateModeAllowedValue(mode, violations);
        validateCrossFieldConsistency(mode, strict, violations);

        if (violations.isEmpty()) {
            return TracingControlDomainValidationResult.success();
        }

        return new TracingControlDomainValidationResult(false, violations);
    }

    private static void validateModeAllowedValue(String mode, List<String> violations) {
        if (mode == null) {
            return;
        }

        if (mode.isBlank()) {
            violations.add("validation.mode must not be blank; allowed values: " + ALLOWED_MODES);
            return;
        }

        if (!ALLOWED_MODES.contains(mode)) {
            violations.add(
                    "validation.mode '%s' is not a recognised mode; allowed values: %s"
                            .formatted(mode, ALLOWED_MODES));
        }
    }

    private static void validateCrossFieldConsistency(String mode,
                                                      Boolean strict,
                                                      List<String> violations) {
        if (mode == null || strict == null) {
            return;
        }

        if (!ALLOWED_MODES.contains(mode)) {
            return;
        }

        boolean modeImpliesStrict = MODE_STRICT.equals(mode);
        if (modeImpliesStrict != strict) {
            violations.add(
                    ("validation.mode '%s' conflicts with validation.strict=%s; "
                            + "LOG_ONLY requires strict=false, STRICT requires strict=true")
                            .formatted(mode, strict));
        }
    }

    private static String modeValue(Map<String, Object> payload) {
        Object raw = payload.get(TracingControlProtocolKeys.VALIDATION_MODE);
        return (raw instanceof String s) ? s : null;
    }

    private static Boolean strictValue(Map<String, Object> payload) {
        Object raw = payload.get(TracingControlProtocolKeys.VALIDATION_STRICT);
        return (raw instanceof Boolean b) ? b : null;
    }
}
