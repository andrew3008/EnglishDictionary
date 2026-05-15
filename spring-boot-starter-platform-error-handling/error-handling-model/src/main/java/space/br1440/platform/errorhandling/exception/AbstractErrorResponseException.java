package space.br1440.platform.errorhandling.exception;

import lombok.Getter;
import org.springframework.util.CollectionUtils;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.error_detail.ErrorDetail;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;
import space.br1440.platform.errorhandling.utils.ClientMetadataBuilder;
import space.br1440.platform.errorhandling.utils.DiagnosticMetadataBuilder;
import space.br1440.platform.errorhandling.utils.ReasonCodeUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;

/**
 * Корневой абстрактный класс платформенных исключений с {@link ErrorEntry}.
 *
 * <p>Несёт структурированный {@link ErrorEntry} — расширенный аналог
 * {@code ProblemDetail} (RFC 9457) с платформенными полями:
 * ({@code errorCode}, {@code details}, {@code domain}).
 *
 * <h2>Трёхосная модель</h2>
 * <p>Классификация разделена на ортогональные оси:
 * <ul>
 *   <li><b>Наследование</b> — {@code extends AbstractErrorResponseException};
 *   доменные обёртки живут у потребителя стартера.</li>
 *   <li><b>Семантика</b> — маркер в своём bucket-пакете ({@code validation.ValidationException},
 *       {@code inconsistency.InconsistencyException}, и т.д.). Исключения в {@code internal/} намеренно
 *       могут не иметь семантического маркера.</li>
 *   <li><b>HTTP</b> — маркеры в {@code exception.http}; ответный статус вычисляет
 *       {@link space.br1440.platform.errorhandling.http.DefaultHttpStatusResolver}, исключение
 *       само HTTP-статус <b>не несёт</b> (нет метода {@code httpStatus()} на классе).</li>
 * </ul>
 *
 * <h2>Автовывод errorCode</h2>
 * <p>{@code errorCode} в {@link ErrorEntry} формируется из имени runtime-класса через
 * {@link ReasonCodeUtils#toReasonCode(Class)}.
 *
 * <h2>clientMetadata vs diagnosticMetadata</h2>
 * <p>Конструкторы принимают два независимых словаря метаданных:
 * <ul>
 *   <li>{@code clientMetadata} — безопасно для передачи клиенту в DTO
 *       (стабильный API-контракт, AIP-193); строится через {@link ClientMetadataBuilder}.</li>
 *   <li>{@code diagnosticMetadata} — только для SRE/разработчиков (логи, трейсинг,
 *       дашборды); строится через {@link DiagnosticMetadataBuilder}; <b>никогда</b> не попадает в DTO.</li>
 * </ul>
 */
@Getter
public abstract class AbstractErrorResponseException extends RuntimeException {

    private final ErrorEntry errorEntry;

    protected AbstractErrorResponseException(String message, Map<String, String> clientMetadata) {
        super(message);
        this.errorEntry = buildEntry(message, clientMetadata, null);
    }

    protected AbstractErrorResponseException(String message,
                                             Map<String, String> clientMetadata,
                                             Throwable cause) {
        super(message, cause);
        this.errorEntry = buildEntry(message, clientMetadata, null);
    }

    protected AbstractErrorResponseException(String message,
                                             Map<String, String> clientMetadata,
                                             List<ErrorDetail> details) {
        super(message);
        this.errorEntry = buildEntryWithDetails(message, clientMetadata, null, details);
    }

    protected AbstractErrorResponseException(String message,
                                             Map<String, String> clientMetadata,
                                             List<ErrorDetail> details,
                                             Throwable cause) {
        super(message, cause);
        this.errorEntry = buildEntryWithDetails(message, clientMetadata, null, details);
    }

    protected AbstractErrorResponseException(String message,
                                             Map<String, String> clientMetadata,
                                             Map<String, String> diagnosticMetadata) {
        super(message);
        this.errorEntry = buildEntry(message, clientMetadata, diagnosticMetadata);
    }

    protected AbstractErrorResponseException(String message,
                                             Map<String, String> clientMetadata,
                                             Map<String, String> diagnosticMetadata,
                                             Throwable cause) {
        super(message, cause);
        this.errorEntry = buildEntry(message, clientMetadata, diagnosticMetadata);
    }

    protected AbstractErrorResponseException(String message,
                                             Map<String, String> clientMetadata,
                                             Map<String, String> diagnosticMetadata,
                                             List<ErrorDetail> details) {
        super(message);
        this.errorEntry = buildEntryWithDetails(message, clientMetadata, diagnosticMetadata, details);
    }

    protected AbstractErrorResponseException(String message,
                                             Map<String, String> clientMetadata,
                                             Map<String, String> diagnosticMetadata,
                                             List<ErrorDetail> details,
                                             Throwable cause) {
        super(message, cause);
        this.errorEntry = buildEntryWithDetails(message, clientMetadata, diagnosticMetadata, details);
    }

    private ErrorEntry buildEntry(String message,
                                  Map<String, String> clientMetadata,
                                  Map<String, String> diagnosticMetadata) {
        String reasonCode = ReasonCodeUtils.toReasonCode(this.getClass());
        List<ErrorDetail> details = buildDefaultDetails(reasonCode, clientMetadata, diagnosticMetadata);

        return ErrorEntry.builder()
                .httpStatus(INTERNAL_SERVER_ERROR)
                .errorCode(reasonCode)
                .message(message)
                .details(details)
                .build();
    }

    private ErrorEntry buildEntryWithDetails(String message,
                                             Map<String, String> clientMetadata,
                                             Map<String, String> diagnosticMetadata,
                                             List<ErrorDetail> additionalDetails) {
        String reasonCode = ReasonCodeUtils.toReasonCode(this.getClass());
        List<ErrorDetail> details = new ArrayList<>(buildDefaultDetails(reasonCode, clientMetadata, diagnosticMetadata));

        if (additionalDetails != null) {
            details.addAll(additionalDetails);
        }

        return ErrorEntry.builder()
                .httpStatus(INTERNAL_SERVER_ERROR)
                .errorCode(reasonCode)
                .message(message)
                .details(Collections.unmodifiableList(details))
                .build();
    }

    private static List<ErrorDetail> buildDefaultDetails(String reasonCode,
                                                         Map<String, String> clientMetadata,
                                                         Map<String, String> diagnosticMetadata) {
        // Поле domain намеренно не выставляем здесь: имя сервиса резолвится позже —
        // либо через ErrorEntryFactory.resolveDomain() (Supplier из Tracing-стартера),
        // либо через RemoteServiceDomainEnricher (для cause-цепочек). Это разрывает
        // зависимость web-error-model от глобального состояния (раньше использовался
        // PlatformErrorDomainHolder, удалённый ради чистого DI-канала).
        ErrorInfoDetail.ErrorInfoDetailBuilder builder = ErrorInfoDetail.builder()
                .reason(reasonCode);

        if (!CollectionUtils.isEmpty(clientMetadata)) {
            builder.clientMetadata(clientMetadata);
        }

        if (!CollectionUtils.isEmpty(diagnosticMetadata)) {
            builder.diagnosticMetadata(diagnosticMetadata);
        }

        return List.of(builder.build());
    }
}
