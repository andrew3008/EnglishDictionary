package space.br1440.platform.errorhandling.core.strategy.top;

import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import space.br1440.platform.errorhandling.core.resolver.CauseChainResolver;
import space.br1440.platform.errorhandling.extension.ErrorHandlingContext;
import space.br1440.platform.errorhandling.extension.base.AbstractTopExceptionStrategy;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.error_detail.ErrorDetail;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;
import space.br1440.platform.errorhandling.model.error_detail.RetryInfoDetail;
import space.br1440.platform.errorhandling.extension.LogPolicy;
import space.br1440.platform.errorhandling.extension.order.TopExceptionStrategyOrder;
import space.br1440.platform.errorhandling.core.util.ClassUtils;
import space.br1440.platform.errorhandling.core.util.DiagnosticMetadataBuilder;
import space.br1440.platform.errorhandling.core.util.ReflectionUtils;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.core.util.StringUtils;
import space.br1440.platform.errorhandling.core.util.sanitize.OAuth2DiagnosticSanitizer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Стратегия обработки OAuth2/JWT исключений как top-level exception.
 *
 * <p>Перехватывает OAuth2-специфичные исключения <b>до</b> generic
 * {@link SpringSecurityExceptionStrategy} (order 200), формируя диагностические
 * метаданные, недоступные при generic маппинге {@code AuthenticationException → 401}.
 *
 * <h2>Разграничение с root-cause стратегией</h2>
 * <ul>
 *   <li>{@code OAuth2AuthorizationExceptionStrategy} (root-cause, ORDER=650) —
 *       обрабатывает OAuth2-исключения как <b>причину</b> внутри бизнес-исключений.</li>
 *   <li>Данная стратегия (top-level, ORDER=250) — обрабатывает OAuth2-исключения,
 *       брошенные <b>напрямую</b> (token exchange failure, JWT validation, resource server).</li>
 * </ul>
 *
 * <h2>Поддерживаемые исключения</h2>
 * <ul>
 *   <li>{@code OAuth2AuthenticationException} — ошибки аутентификации через OAuth2
 *       (invalid/expired token, insufficient scope на resource server)</li>
 *   <li>{@code ClientAuthorizationRequiredException} — требуется авторизация OAuth2 клиента</li>
 *   <li>{@code ClientAuthorizationException} — ошибка client credentials / token exchange</li>
 * </ul>
 *
 * <h2>Безопасность (CWE-209 / CWE-312)</h2>
 * <p>Токены, access_token parameters и JWT compact serialization redacted.
 * В метаданные включаются только: error code (RFC 6749), client registration ID,
 * тип ошибки. Значения токенов и claims не включаются.
 */
public class OAuth2ClientExceptionStrategy extends AbstractTopExceptionStrategy {

    private static final ClassLoader LOADER = OAuth2ClientExceptionStrategy.class.getClassLoader();

    @Nullable
    private static final Class<?> OAUTH2_AUTHENTICATION_EXCEPTION =
            ReflectionUtils.loadOptionalClass(
                    "org.springframework.security.oauth2.core.OAuth2AuthenticationException", LOADER);

    @Nullable
    private static final Class<?> CLIENT_AUTHORIZATION_REQUIRED_EXCEPTION =
            ReflectionUtils.loadOptionalClass(
                    "org.springframework.security.oauth2.client.ClientAuthorizationRequiredException", LOADER);

    @Nullable
    private static final Class<?> CLIENT_AUTHORIZATION_EXCEPTION =
            ReflectionUtils.loadOptionalClass(
                    "org.springframework.security.oauth2.client.ClientAuthorizationException", LOADER);

    private static final int MAX_MESSAGE_LENGTH = 300;

    public OAuth2ClientExceptionStrategy(@NonNull CauseChainResolver causeChainResolver) {
        super(TopExceptionStrategyOrder.OAUTH2, causeChainResolver);
    }

    @Override
    @NonNull
    public LogPolicy logPolicy(@NonNull Exception exception, @NonNull ErrorEntry entry) {
        return entry.getHttpStatus().is5xxServerError() ? LogPolicy.ERROR : LogPolicy.WARN;
    }

    @Override
    public boolean supports(@NonNull Exception exception) {
        return ClassUtils.isInstanceOf(OAUTH2_AUTHENTICATION_EXCEPTION, exception)
                || ClassUtils.isInstanceOf(CLIENT_AUTHORIZATION_REQUIRED_EXCEPTION, exception)
                || ClassUtils.isInstanceOf(CLIENT_AUTHORIZATION_EXCEPTION, exception);
    }

    @Override
    @NonNull
    protected ErrorEntry doHandle(@NonNull Exception exception, @NonNull ErrorHandlingContext context) {
        OAuth2ErrorType type = classify(exception);

        List<ErrorDetail> details = new ArrayList<>();
        if (type.retryable) {
            details.add(new RetryInfoDetail());
        }

        ErrorInfoDetail info = ErrorInfoDetail.builder()
                .reason(type.reason)
                .domain("oauth2")
                .diagnosticMetadata(buildDiagnosticMetadata(exception, type, context.getStackHintExtractor()))
                .build();
        details.add(info);

        return baseEntry(type.httpStatus, type.errorCode, type.message, context)
                .details(Collections.unmodifiableList(details))
                .build();
    }

    @NonNull
    private static OAuth2ErrorType classify(@NonNull Exception exception) {
        if (ClassUtils.isInstanceOf(CLIENT_AUTHORIZATION_REQUIRED_EXCEPTION, exception)) {
            return OAuth2ErrorType.AUTHORIZATION_REQUIRED;
        }

        if (ClassUtils.isInstanceOf(CLIENT_AUTHORIZATION_EXCEPTION, exception)) {
            return classifyClientAuthorizationException(exception);
        }

        if (ClassUtils.isInstanceOf(OAUTH2_AUTHENTICATION_EXCEPTION, exception)) {
            return classifyByErrorCode(exception);
        }

        return OAuth2ErrorType.GENERIC;
    }

    @NonNull
    private static OAuth2ErrorType classifyClientAuthorizationException(@NonNull Exception exception) {
        Throwable cause = exception.getCause();
        if (cause != null) {
            String causeClassName = cause.getClass().getName();
            if (causeClassName.contains("ConnectException")
                    || causeClassName.contains("UnknownHostException")
                    || causeClassName.contains("SocketTimeoutException")) {
                return OAuth2ErrorType.AUTHORIZATION_SERVER_UNAVAILABLE;
            }
        }

        return OAuth2ErrorType.CLIENT_AUTHORIZATION_FAILED;
    }

    @NonNull
    private static OAuth2ErrorType classifyByErrorCode(@NonNull Exception exception) {
        final OAuth2ErrorType[] result = {OAuth2ErrorType.GENERIC};

        ReflectionUtils.invokeMethod(exception, "getError", error -> {
            if (error == null) return;
            ReflectionUtils.invokeMethod(error, "getErrorCode", code -> {
                if (code instanceof String s) {
                    result[0] = mapRfc6749Code(s);
                }
            });
        });

        return result[0];
    }

    /**
     * @see <a href="https://www.rfc-editor.org/rfc/rfc6749#section-5.2">RFC 6749 §5.2</a>
     * @see <a href="https://www.rfc-editor.org/rfc/rfc6750#section-3.1">RFC 6750 §3.1</a>
     * @see <a href="https://www.rfc-editor.org/rfc/rfc9449#section-7.1">RFC 9449 §7.1 (DPoP)</a>
     */
    @NonNull
    private static OAuth2ErrorType mapRfc6749Code(@Nullable String code) {
        if (code == null) {
            return OAuth2ErrorType.GENERIC;
        }

        return switch (code) {
            case "invalid_token"              -> OAuth2ErrorType.INVALID_TOKEN;
            case "expired_token",
                 "token_expired"              -> OAuth2ErrorType.TOKEN_EXPIRED;
            case "insufficient_scope"         -> OAuth2ErrorType.INSUFFICIENT_SCOPE;
            case "access_denied"              -> OAuth2ErrorType.ACCESS_DENIED;
            case "invalid_grant"              -> OAuth2ErrorType.INVALID_GRANT;
            case "invalid_request"            -> OAuth2ErrorType.INVALID_REQUEST;
            case "invalid_scope"              -> OAuth2ErrorType.INVALID_SCOPE;
            case "unauthorized_client"        -> OAuth2ErrorType.UNAUTHORIZED_CLIENT;
            case "unsupported_grant_type",
                 "unsupported_response_type"  -> OAuth2ErrorType.UNSUPPORTED_GRANT_TYPE;
            case "invalid_client"             -> OAuth2ErrorType.CLIENT_AUTHORIZATION_FAILED;
            case "server_error",
                 "temporarily_unavailable"    -> OAuth2ErrorType.AUTHORIZATION_SERVER_UNAVAILABLE;
            case "invalid_dpop_proof"         -> OAuth2ErrorType.INVALID_DPOP_PROOF;
            case "use_dpop_nonce"             -> OAuth2ErrorType.USE_DPOP_NONCE;
            case "mtls_required"              -> OAuth2ErrorType.MTLS_REQUIRED;
            default                           -> OAuth2ErrorType.GENERIC;
        };
    }

    @NonNull
    private static Map<String, String> buildDiagnosticMetadata(@NonNull Exception exception,
                                                               @NonNull OAuth2ErrorType type,
                                                               @NonNull StackHintExtractor stackHintExtractor) {
        DiagnosticMetadataBuilder builder = DiagnosticMetadataBuilder.forCause(exception, stackHintExtractor);
        builder.put("oauth2ErrorType", type.reason);

        ReflectionUtils.invokeMethod(exception, "getError", error -> {
            if (error == null) {
                return;
            }

            ReflectionUtils.invokeMethod(error, "getErrorCode", code -> {
                if (code instanceof String s && StringUtils.hasText(s)) {
                    builder.put("oauth2ErrorCode", s);
                }
            });
        });

        ReflectionUtils.invokeMethod(exception, "getClientRegistrationId", id -> {
            if (id instanceof String s && StringUtils.hasText(s)) {
                builder.put("clientRegistrationId", s);
            }
        });

        String safeMessage = OAuth2DiagnosticSanitizer.sanitize(exception.getMessage(), MAX_MESSAGE_LENGTH);
        if (safeMessage != null) {
            builder.put("causeMessage", safeMessage);
        }

        return builder.withStackHint().build();
    }

    private enum OAuth2ErrorType {
        /** RFC 6750 §3.1: token expired — клиент может повторить с refreshed token. */
        TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "TOKEN_EXPIRED", true,
                "The access token has expired. Obtain a new token and retry."),

        INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "INVALID_TOKEN", false,
                "The access token is invalid or has been revoked."),

        INSUFFICIENT_SCOPE(HttpStatus.FORBIDDEN, "PERMISSION_DENIED", "INSUFFICIENT_SCOPE", false,
                "The access token does not have sufficient scope for this operation."),

        ACCESS_DENIED(HttpStatus.FORBIDDEN, "PERMISSION_DENIED", "ACCESS_DENIED", false,
                "Access was denied by the authorization server."),

        INVALID_GRANT(HttpStatus.BAD_REQUEST, "UNAUTHENTICATED", "INVALID_GRANT", false,
                "The provided authorization grant is invalid, expired, or revoked."),

        /** RFC 6749 §5.2: invalid_request — malformed token request. */
        INVALID_REQUEST(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "INVALID_REQUEST", false,
                "The OAuth2 token request is missing a parameter or is otherwise malformed."),

        /** RFC 6749 §5.2: invalid_scope — requested scope is invalid/unknown. */
        INVALID_SCOPE(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "INVALID_SCOPE", false,
                "The requested OAuth2 scope is invalid, unknown, or malformed."),

        /** RFC 6749 §5.2: unauthorized_client — client not authorized for this grant. */
        UNAUTHORIZED_CLIENT(HttpStatus.FORBIDDEN, "PERMISSION_DENIED", "UNAUTHORIZED_CLIENT", false,
                "The OAuth2 client is not authorized to use the requested grant type."),

        /** RFC 6749 §5.2: unsupported_grant_type / unsupported_response_type. */
        UNSUPPORTED_GRANT_TYPE(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "UNSUPPORTED_GRANT_TYPE", false,
                "The requested OAuth2 grant or response type is not supported by the authorization server."),

        AUTHORIZATION_REQUIRED(HttpStatus.BAD_GATEWAY, "UNAVAILABLE", "AUTHORIZATION_REQUIRED", false,
                "The service was unable to obtain authorization for an upstream dependency."),

        CLIENT_AUTHORIZATION_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "UNAVAILABLE", "CLIENT_AUTHORIZATION_FAILED", false,
                "OAuth2 client failed to obtain authorization. This may indicate a misconfiguration."),

        AUTHORIZATION_SERVER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "UNAVAILABLE", "AUTHORIZATION_SERVER_UNAVAILABLE", true,
                "The authorization server is temporarily unavailable. Please try again later."),

        /** RFC 9449 §7.1: invalid_dpop_proof. */
        INVALID_DPOP_PROOF(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "INVALID_DPOP_PROOF", false,
                "The provided DPoP proof is invalid."),

        /** RFC 9449 §8: use_dpop_nonce — клиент должен повторить с server-issued nonce. */
        USE_DPOP_NONCE(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "USE_DPOP_NONCE", true,
                "Authorization server requires a fresh DPoP nonce. Retry with the provided nonce."),

        /** RFC 8705: mTLS-bound token rejected when mTLS not established. */
        MTLS_REQUIRED(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "MTLS_REQUIRED", false,
                "Mutual TLS is required to use the presented client credentials."),

        GENERIC(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "OAUTH2_ERROR", false,
                "An unexpected OAuth2 authorization error occurred.");

        final HttpStatus httpStatus;
        final String errorCode;
        final String reason;
        final boolean retryable;
        final String message;

        OAuth2ErrorType(HttpStatus httpStatus,
                        String errorCode,
                        String reason,
                        boolean retryable,
                        String message) {
            this.httpStatus = httpStatus;
            this.errorCode = errorCode;
            this.reason = reason;
            this.retryable = retryable;
            this.message = message;
        }
    }
}
