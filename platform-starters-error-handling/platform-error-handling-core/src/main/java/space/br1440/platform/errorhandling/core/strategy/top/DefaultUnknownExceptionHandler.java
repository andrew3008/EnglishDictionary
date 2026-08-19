package space.br1440.platform.errorhandling.core.strategy.top;

import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import space.br1440.platform.errorhandling.extension.ErrorHandlingContext;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.error_detail.ErrorDetail;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;
import space.br1440.platform.errorhandling.core.resolver.CauseChainResolver;
import space.br1440.platform.errorhandling.extension.ResolvedRootCause;
import space.br1440.platform.errorhandling.extension.UnknownExceptionHandler;
import space.br1440.platform.errorhandling.core.util.CauseChainNavigator;
import space.br1440.platform.errorhandling.core.util.StringUtils;
import space.br1440.platform.errorhandling.utils.CanonicalErrors;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Реализация {@link UnknownExceptionHandler} по умолчанию.
 *
 * <p>Оборачивает любое неклассифицированное исключение в стандартный
 * ответ {@code 500 Internal Server Error}.
 * Публичное сообщение — нейтральное ({@code "Internal server error"}).
 * Технический контекст — в {@code reason}: FQCN root-cause исключения ({@code rootCause.getClass().getName()}).
 *
 * <p>Умышленно: {@code reason} — deepest ({@link CauseChainNavigator}); доп. {@code details} —
 * распознанные chanins всей цепочки, начиная с самого исключения
 * ({@link CauseChainResolver#resolveAllStrict}). RestClient/RestTemplate 4xx/5xx
 * бросаются как top-level {@code RestClientResponseException} без полезного {@code cause}
 * — если смотреть только {@code getCause()}, HTTP-integration detail не появится.
 *
 * <h2>CWE-209</h2>
 * <p>Stack trace, SQL-запросы и внутренние сообщения исключений намеренно
 * не включаются в тело ответа.
 */
public class DefaultUnknownExceptionHandler implements UnknownExceptionHandler {

    private static final String PUBLIC_MESSAGE = "Internal server error";

    private final CauseChainResolver causeChainResolver;

    public DefaultUnknownExceptionHandler(@NonNull CauseChainResolver causeChainResolver) {
        this.causeChainResolver = causeChainResolver;
    }

    @Override
    @NonNull
    public @NotNull ErrorEntry handle(@NonNull Exception exception, @NonNull ErrorHandlingContext context) {
        Throwable rootCause = CauseChainNavigator.findDeepestCause(exception);

        // CWE-209: reason содержит только имя класса без getMessage(), чтобы не утечь
        // внутренние детали (SQL-запросы, пути файлов, токены) через поле reason.
        String reason = rootCause.getClass().getName();

        List<ErrorDetail> details = new ArrayList<>();

        ErrorInfoDetail info = buildInfoDetail(exception, rootCause, reason);
        details.add(info);

        // Из распознанных chains берутся только details. httpStatus остаётся 500:
        // он обязан согласовываться с errorCode=UNKNOWN_INTERNAL_ERROR и нейтральным message,
        // а принятие статуса звена сделало бы код ответа зависящим от глубины цепочки.
        for (ResolvedRootCause resolvedRootCause : causeChainResolver.resolveAllStrict(exception)) {
            details.addAll(resolvedRootCause.resolution().details());
        }

        return ErrorEntry.builder()
                .httpStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                .errorCode(CanonicalErrors.UNKNOWN_INTERNAL_ERROR)
                .message(PUBLIC_MESSAGE)
                .reason(reason)
                .requestURI(context.getRequestPath())
                .details(Collections.unmodifiableList(details))
                .build();
    }

    private ErrorInfoDetail buildInfoDetail(@NonNull Exception exception,
                                            @NonNull Throwable rootCause,
                                            @NonNull String reason) {
        ErrorInfoDetail.ErrorInfoDetailBuilder builder = ErrorInfoDetail.builder()
                .reason(reason)
                .domain("unknown")
                .clientMetadata(Collections.singletonMap("exceptionType", exception.getClass().getName()));

        String rootCauseMessage = rootCause.getMessage();
        if (StringUtils.hasText(rootCauseMessage)) {
            builder.diagnosticMetadata(Collections.singletonMap("rootCauseMessage", rootCauseMessage));
        }

        return builder.build();
    }
}
