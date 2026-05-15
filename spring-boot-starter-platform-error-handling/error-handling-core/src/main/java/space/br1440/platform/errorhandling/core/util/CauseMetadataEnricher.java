package space.br1440.platform.errorhandling.core.util;

import lombok.experimental.UtilityClass;
import org.springframework.lang.NonNull;

import java.util.Map;

/**
 * Утилита для обогащения диагностических метаданных информацией из цепочки причин исключения.
 */
@UtilityClass
public final class CauseMetadataEnricher {

    /**
     * Глубина обхода cause chain для диагностических стратегий.
     * Единое значение для всех стратегий, использующих {@link CauseChainNavigator#findDeepestCause}.
     */
    public static final int DIAGNOSTIC_CAUSE_CHAIN_DEPTH = 5;

    public static void enrichWithRootCause(@NonNull Throwable cause,
                                           @NonNull Map<String, String> metadata,
                                           int maxCauseMessageLength) {
        enrichWithRootCause(cause, metadata, DIAGNOSTIC_CAUSE_CHAIN_DEPTH, maxCauseMessageLength);
    }

    public static void enrichWithRootCause(@NonNull Throwable cause,
                                           @NonNull Map<String, String> metadata,
                                           int maxDepth,
                                           int maxCauseMessageLength) {
        Throwable deepest = CauseChainNavigator.findDeepestCause(cause, maxDepth);
        if (deepest == cause) {
            return;
        }

        metadata.put("rootCauseClass", deepest.getClass().getName());

        String rootMessage = deepest.getMessage();
        String causeMessage = cause.getMessage();
        if (StringUtils.hasText(rootMessage) && !rootMessage.equals(causeMessage)) {
            metadata.put("rootCauseMessage", StringUtils.truncate(rootMessage, maxCauseMessageLength));
        }
    }

    /**
     * Добавляет в metadata только {@code rootCauseClass} — структурный FQCN глубинной причины
     * без её текстового {@code message}.
     *
     * <p>Применяется в стратегиях с повышенными требованиями к {@code CWE-209/CWE-200}, где
     * текст root-cause может содержать чувствительные данные (TLS-сертификаты: CN/SAN/serial,
     * Vault: токены, JDBC URL: креды), а класс исключения — безопасный диагностический сигнал.
     *
     * @param cause   корень обработки (точка входа стратегии)
     * @param metadata мутируемая map с диагностикой
     * @param maxDepth максимальная глубина обхода cause chain
     */
    public static void enrichWithRootCauseClassOnly(@NonNull Throwable cause,
                                                    @NonNull Map<String, String> metadata,
                                                    int maxDepth) {
        Throwable deepest = CauseChainNavigator.findDeepestCause(cause, maxDepth);
        if (deepest == cause) {
            return;
        }

        metadata.put("rootCauseClass", deepest.getClass().getName());
    }

    public static void enrichWithNestedCause(@NonNull Throwable cause,
                                             @NonNull Map<String, String> metadata,
                                             int maxCauseMessageLength) {
        Throwable nested = cause.getCause();
        if (nested == null || nested == cause) {
            return;
        }

        metadata.put("nestedCauseClass", nested.getClass().getName());

        String nestedMessage = nested.getMessage();
        if (StringUtils.hasText(nestedMessage)) {
            metadata.put("nestedCauseMessage", StringUtils.truncate(nestedMessage, maxCauseMessageLength));
        }
    }
}
