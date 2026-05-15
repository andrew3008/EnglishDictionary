package space.br1440.platform.errorhandling.model.error_detail;

import java.util.Map;

/**
 * Расширяемый тип детали ошибки для downstream: реализуется в сервисах, не в платформенных sealed-классах.
 *
 * <p>На wire-уровне сериализуется как {@code type=CUSTOM} с полями {@link #detailType()} и {@link #wireMetadata()}.
 */
public non-sealed interface CustomErrorDetail extends ErrorDetail {

    /**
     * Подтип внутри {@code CUSTOM} (стабильная строка для клиентов и контрактов).
     */
    String detailType();

    /**
     * Безопасные для JSON метаданные (без PII по умолчанию).
     */
    default Map<String, String> wireMetadata() {
        return Map.of();
    }
}
