package space.br1440.platform.errorhandling.core.strategy.top;

import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.*;
import org.springframework.security.core.AuthenticationException;
import space.br1440.platform.errorhandling.core.resolver.CauseChainResolver;
import space.br1440.platform.errorhandling.extension.ErrorHandlingContext;
import space.br1440.platform.errorhandling.extension.LogPolicy;
import space.br1440.platform.errorhandling.extension.base.AbstractTopExceptionStrategy;
import space.br1440.platform.errorhandling.extension.order.TopExceptionStrategyOrder;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;

import java.util.*;

/**
 * Стратегия обработки исключений Spring Security.
 *
 * <p>Активируется только при наличии {@code spring-security-core} на classpath
 * ({@code @ConditionalOnClass} в конфигурации).
 *
 * <h2>Взаимодействие с ExceptionTranslationFilter</h2>
 * <p>
 * Spring Security {@code ExceptionTranslationFilter} перехватывает эти исключения
 * на уровне фильтров (до {@code DispatcherServlet}). Данная стратегия обрабатывает случаи,
 * когда исключения пробрасываются из {@code @PreAuthorize}, {@code @Secured}
 * или кастомной security-логики внутри контроллеров.
 *
 * <h2>Безопасность (CWE-203)</h2>
 * <p>
 * Оригинальные сообщения Spring Security не включаются в ответ для предотвращения
 * account enumeration. Machine-readable причина передаётся через {@code metadata.reason}.
 */
public class SpringSecurityExceptionStrategy extends AbstractTopExceptionStrategy {

    private static final Set<Class<? extends Exception>> SUPPORTED_TYPES = Set.of(
            AuthenticationException.class,
            AccessDeniedException.class
    );

    private final LogPolicy authFailureLogPolicy;
    private final LogPolicy accessDeniedLogPolicy;

    public SpringSecurityExceptionStrategy(@NonNull CauseChainResolver causeChainResolver,
                                           @NonNull LogPolicy authFailureLogPolicy,
                                           @NonNull LogPolicy accessDeniedLogPolicy) {
        super(TopExceptionStrategyOrder.SECURITY, causeChainResolver);
        this.authFailureLogPolicy = authFailureLogPolicy;
        this.accessDeniedLogPolicy = accessDeniedLogPolicy;
    }

    /**
     * Уровень логирования задаётся конфигурацией ({@code platform.error-handling.security.*}).
     *
     * <p>Default: {@link LogPolicy#WARN} для обоих типов — обеспечивает видимость событий
     * безопасности в production-логах и SIEM-системах. {@link LogPolicy#DEBUG} допускается
     * только в dev-окружениях.
     */
    @Override
    @NonNull
    public LogPolicy logPolicy(@NonNull Exception exception, @NonNull ErrorEntry entry) {
        if (exception instanceof AuthenticationException) {
            return authFailureLogPolicy;
        } else {
            return accessDeniedLogPolicy;
        }
    }

    @Override
    public boolean supports(@NonNull Exception exception) {
        return SUPPORTED_TYPES.stream().anyMatch(type -> type.isInstance(exception));
    }

    @Override
    @NonNull
    protected ErrorEntry doHandle(@NonNull Exception exception, @NonNull ErrorHandlingContext context) {
        if (exception instanceof AuthenticationException authenticationException) {
            return createAuthenticationEntry(authenticationException, context);
        } else {
            return createAccessDeniedEntry(context);
        }
    }

    @NonNull
    private ErrorEntry createAuthenticationEntry(@NonNull AuthenticationException exception,
                                                 @NonNull ErrorHandlingContext context) {
        String reason = resolveAuthenticationReason(exception);
        return createEntry(
                HttpStatus.UNAUTHORIZED,
                "UNAUTHENTICATED",
                "Full authentication is required to access this resource",
                reason,
                Map.of("reason", reason),
                context);
    }

    @NonNull
    private ErrorEntry createAccessDeniedEntry(@NonNull ErrorHandlingContext context) {
        return createEntry(
                HttpStatus.FORBIDDEN,
                "PERMISSION_DENIED",
                "You do not have permission to access this resource",
                "Access denied",
                Map.of(),
                context);
    }

    @NonNull
    private static ErrorEntry createEntry(HttpStatus httpStatus,
                                          @NonNull String errorCode,
                                          @NonNull String message,
                                          @NonNull String reason,
                                          @NonNull Map<String, String> metadata,
                                          @NonNull ErrorHandlingContext context) {
        ErrorInfoDetail.ErrorInfoDetailBuilder infoBuilder = ErrorInfoDetail.builder()
                .reason(reason)
                .domain("security");

        if (!metadata.isEmpty()) {
            infoBuilder.clientMetadata(Collections.unmodifiableMap(new LinkedHashMap<>(metadata)));
        }

        ErrorInfoDetail info = infoBuilder.build();
        return ErrorEntry.builder()
                .httpStatus(httpStatus)
                .errorCode(errorCode)
                .message(message)
                .reason(reason)
                .requestURI(context.getRequestPath())
                .details(List.of(info))
                .build();
    }

    @NonNull
    private String resolveAuthenticationReason(@NonNull AuthenticationException exception) {
        return switch (exception) {
            case BadCredentialsException ignored -> "BAD_CREDENTIALS";
            case InsufficientAuthenticationException ignored -> "INSUFFICIENT_AUTHENTICATION";
            case AccountExpiredException ignored -> "ACCOUNT_EXPIRED";
            case CredentialsExpiredException ignored -> "CREDENTIALS_EXPIRED";
            case DisabledException ignored -> "ACCOUNT_DISABLED";
            case LockedException ignored -> "ACCOUNT_LOCKED";
            default -> "AUTHENTICATION_FAILED";
        };
    }
}
