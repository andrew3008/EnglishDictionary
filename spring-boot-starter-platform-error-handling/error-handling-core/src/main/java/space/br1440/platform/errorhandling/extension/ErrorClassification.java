package space.br1440.platform.errorhandling.extension;

import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import space.br1440.platform.errorhandling.model.error_detail.ErrorDetail;

import java.util.List;

/**
 * Compile-time контракт для inner enum-классификаторов ошибок внутри реализаций
 * {@link RootCauseExceptionHandlingStrategy}.
 *
 * <p>Работает на уровне ниже, чем
 * {@link space.br1440.platform.errorhandling.extension.base.AbstractRootCauseExceptionStrategy}:
 * <ul>
 *   <li>{@code AbstractRootCauseExceptionStrategy} — контракт стратегии
 *       ({@code supportsCause} + {@code extractCauseDetails})</li>
 *   <li>{@code ErrorClassification} — контракт внутреннего классификатора ошибок
 *       внутри стратегии</li>
 * </ul>
 *
 * <h3>{@code message()} и локализация</h3>
 * <p>{@link #message()} предназначен для логирования и {@code diagnosticMetadata};
 * не гарантирует локализацию. Если в будущем потребуется i18n, контракт будет
 * расширен отдельным методом (например {@code messageKey()}).
 *
 * <h3>Порядок полей в enum-конструкторе</h3>
 * <p>Порядок конструктора enum не диктуется этим интерфейсом. Каждый enum
 * определяет порядок самостоятельно.
 *
 * <h3>Область применения</h3>
 * <p>Предназначен для стратегий, enum которых хранит все три поля
 * ({@code httpStatus}, {@code message}, {@code retryability}) как конструкторные параметры.
 * Стратегии с иным паттерном (bare enum без полей, enum с неполным набором,
 * отсутствие enum) не обязаны реализовывать этот интерфейс.
 *
 * @see RootCauseExceptionHandlingStrategy
 * @see RootCauseResolution
 */
public interface ErrorClassification {

    @NonNull
    HttpStatus httpStatus();

    /**
     * Сообщение об ошибке для логирования и diagnosticMetadata.
     * Не предназначен для клиентской локализации (i18n).
     */
    @NonNull
    String message();

    @NonNull
    Retryability retryability();

    @NonNull
    default RootCauseResolution toResolution(@NonNull List<ErrorDetail> details) {
        return RootCauseResolution.of(httpStatus(), message(), details, retryability());
    }
}
