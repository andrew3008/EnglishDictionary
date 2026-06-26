package space.br1440.platform.errorhandling.core.strategy.cause.security;

import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import space.br1440.platform.errorhandling.core.util.*;
import space.br1440.platform.errorhandling.core.util.sanitize.OAuth2DiagnosticSanitizer;
import space.br1440.platform.errorhandling.extension.LogPolicy;
import space.br1440.platform.errorhandling.extension.Retryability;
import space.br1440.platform.errorhandling.extension.RootCauseResolution;
import space.br1440.platform.errorhandling.extension.base.AbstractRootCauseExceptionStrategy;
import space.br1440.platform.errorhandling.extension.order.RootCauseExceptionStrategyOrder;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.error_detail.ErrorDetail;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;
import space.br1440.platform.errorhandling.model.error_detail.RetryInfoDetail;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Стратегия обработки OAuth2 / JWT / базовых Spring Security исключений
 * как первопричины бизнес-исключений.
 *
 * <p>Покрытые типы исключений (загружаются опционально через рефлексию,
 * чтобы starter не зависел жёстко от Spring Security):
 * <ul>
 *   <li>OAuth2 Core: {@code OAuth2AuthorizationException}, {@code OAuth2AuthenticationException}</li>
 *   <li>OAuth2 Client: {@code ClientAuthorizationException}, {@code ClientAuthorizationRequiredException}</li>
 *   <li>JWT (Resource Server): {@code BadJwtException}, {@code JwtValidationException}, {@code JwtException}</li>
 *   <li>Spring Security Core (non-OAuth2): {@code BadCredentialsException},
 *       {@code InsufficientAuthenticationException}</li>
 * </ul>
 *
 * <h2>Domain в ErrorInfoDetail</h2>
 * <ul>
 *   <li>{@code oauth2} — для OAuth2/JWT-specific типов;</li>
 *   <li>{@code security} — для {@code BadCredentials}/{@code InsufficientAuthentication}
 *       (формально не из OAuth2, но обрабатываются здесь же, потому что
 *       обе ветки маппятся на одинаковую модель {@link OAuth2ErrorType}).</li>
 * </ul>
 *
 * <h2>Диагностические метаданные</h2>
 *
 * <h3>Общие поля</h3>
 * <ul>
 *   <li>{@code exceptionClass} — FQCN типа исключения</li>
 *   <li>{@code causeMessage} — sanitized сообщение (Bearer/JWT/{access,refresh,id}_token redacted,
 *       управляющие символы и Trojan-Source-bidirectional-overrides удалены, truncated до 300)</li>
 *   <li>{@code oauth2ErrorCode} — RFC 6749 / RFC 6750 / RFC 9449 error code (если доступен)</li>
 *   <li>{@code clientRegistrationId} — ID регистрации OAuth2 клиента</li>
 *   <li>{@code missingScopes} — required scope для доступа к ресурсу (RFC 6750 §3 Bearer challenge)</li>
 *   <li>{@code stackHint} — значимые фреймы стектрейса</li>
 * </ul>
 *
 * <h3>Bearer challenge поля (RFC 6750 §3, RFC 9596)</h3>
 * <ul>
 *   <li>{@code bearerError} — {@code WWW-Authenticate: Bearer error="..."}</li>
 *   <li>{@code bearerErrorDescription} — sanitized error_description</li>
 *   <li>{@code bearerScope} — scope challenge value</li>
 * </ul>
 *
 * <h3>Infrastructure failure поля (AUTHORIZATION_SERVER_UNAVAILABLE,
 * CLIENT_AUTHORIZATION_FAILED, GENERIC)</h3>
 * <ul>
 *   <li>{@code nestedCauseClass} — FQCN глубинной сетевой причины</li>
 *   <li>{@code nestedCauseType} — категория причины по whitelist
 *       ({@code CONNECTION_REFUSED}, {@code TLS_ERROR}, {@code SOCKET_TIMEOUT},
 *       {@code DNS_RESOLUTION_FAILED})</li>
 *   <li>{@code nestedCauseMessage} — сообщение (только для network-transient классов)</li>
 *   <li>{@code nestedCauseDepthExceeded} — выставляется в {@code true}, если
 *       cause-chain длиннее лимита {@link CauseMetadataEnricher#DIAGNOSTIC_CAUSE_CHAIN_DEPTH}
 *       и реальная глубинная причина не была определена</li>
 * </ul>
 *
 * <h2>CWE-209 / CWE-312 / CWE-117: Sanitization</h2>
 * <p>Делегируется {@link OAuth2DiagnosticSanitizer}: redaction Bearer-токенов,
 * compact JWT (header.payload.signature), token-параметров (access/refresh/id_token,
 * client_secret, assertion); удаление CR/LF/Trojan-Source-overrides; truncate.
 * Значения JWT claims НЕ включаются — только schema-level имена.
 */
public class OAuth2AuthorizationExceptionStrategy extends AbstractRootCauseExceptionStrategy {

    private static final ClassLoader LOADER = OAuth2AuthorizationExceptionStrategy.class.getClassLoader();

    @Nullable
    private static final Class<?> OAUTH2_AUTHORIZATION_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.springframework.security.oauth2.core.OAuth2AuthorizationException", LOADER);
    @Nullable
    private static final Class<?> OAUTH2_AUTHENTICATION_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.springframework.security.oauth2.core.OAuth2AuthenticationException", LOADER);

    @Nullable
    private static final Class<?> CLIENT_AUTHORIZATION_REQUIRED_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.springframework.security.oauth2.client.ClientAuthorizationRequiredException", LOADER);
    @Nullable
    private static final Class<?> CLIENT_AUTHORIZATION_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.springframework.security.oauth2.client.ClientAuthorizationException", LOADER);

    @Nullable
    private static final Class<?> JWT_VALIDATION_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.springframework.security.oauth2.jwt.JwtValidationException", LOADER);
    @Nullable
    private static final Class<?> BAD_JWT_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.springframework.security.oauth2.jwt.BadJwtException", LOADER);
    @Nullable
    private static final Class<?> JWT_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.springframework.security.oauth2.jwt.JwtException", LOADER);

    @Nullable
    private static final Class<?> BAD_CREDENTIALS_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.springframework.security.authentication.BadCredentialsException", LOADER);
    @Nullable
    private static final Class<?> INSUFFICIENT_AUTHENTICATION_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.springframework.security.authentication.InsufficientAuthenticationException", LOADER);

    private static final int MAX_CAUSE_MESSAGE_LENGTH = 300;
    private static final int MAX_BEARER_DESCRIPTION_LENGTH = 300;

    private static final Set<String> NETWORK_TRANSIENT_CLASSES = Set.of(
            "java.net.ConnectException",
            "java.net.SocketException",
            "java.net.SocketTimeoutException",
            "java.net.UnknownHostException",
            "javax.net.ssl.SSLHandshakeException",
            "javax.net.ssl.SSLException",
            "java.io.IOException"
    );

    private static final Map<String, String> NESTED_CAUSE_TYPE_MAP = Map.of(
            "java.net.ConnectException", "CONNECTION_REFUSED",
            "java.net.SocketTimeoutException", "SOCKET_TIMEOUT",
            "java.net.UnknownHostException", "DNS_RESOLUTION_FAILED",
            "javax.net.ssl.SSLHandshakeException", "TLS_ERROR",
            "javax.net.ssl.SSLException", "TLS_ERROR"
    );

    public OAuth2AuthorizationExceptionStrategy(@NonNull StackHintExtractor stackHintExtractor) {
        super(RootCauseExceptionStrategyOrder.OAUTH2_AUTH, stackHintExtractor);
    }

    @Override
    public boolean supportsCause(@NonNull Throwable cause) {
        return isHandledType(cause);
    }

    @Override
    @NonNull
    public @NotNull LogPolicy logPolicy(@NonNull Exception exception, @NonNull ErrorEntry errorEntry) {
        return classify(exception).logPolicy();
    }

    @Override
    @NonNull
    public RootCauseResolution extractCauseDetails(@NonNull Throwable cause) {
        OAuth2ErrorType type = classify(cause);

        List<ErrorDetail> details = new ArrayList<>(2);
        if (type.retryability() == Retryability.RETRYABLE) {
            details.add(new RetryInfoDetail());
        }

        ErrorInfoDetail info = ErrorInfoDetail.builder()
                .reason(type.name())
                .domain(type.domain())
                .diagnosticMetadata(buildDiagnosticMetadata(cause, type))
                .build();
        details.add(info);

        return RootCauseResolution.of(type.httpStatus(), type.message(), details, type.retryability());
    }

    private static boolean isHandledType(@NonNull Throwable cause) {
        return ClassUtils.isInstanceOf(BAD_JWT_EXCEPTION, cause)
                || ClassUtils.isInstanceOf(JWT_VALIDATION_EXCEPTION, cause)
                || ClassUtils.isInstanceOf(JWT_EXCEPTION, cause)
                || ClassUtils.isInstanceOf(BAD_CREDENTIALS_EXCEPTION, cause)
                || ClassUtils.isInstanceOf(INSUFFICIENT_AUTHENTICATION_EXCEPTION, cause)
                || ClassUtils.isInstanceOf(CLIENT_AUTHORIZATION_REQUIRED_EXCEPTION, cause)
                || ClassUtils.isInstanceOf(CLIENT_AUTHORIZATION_EXCEPTION, cause)
                || ClassUtils.isInstanceOf(OAUTH2_AUTHENTICATION_EXCEPTION, cause)
                || ClassUtils.isInstanceOf(OAUTH2_AUTHORIZATION_EXCEPTION, cause);
    }

    @NonNull
    private static OAuth2ErrorType classify(@NonNull Throwable cause) {
        if (ClassUtils.isInstanceOf(BAD_JWT_EXCEPTION, cause)) {
            return OAuth2ErrorType.INVALID_TOKEN;
        }

        if (ClassUtils.isInstanceOf(JWT_VALIDATION_EXCEPTION, cause)) {
            return classifyJwtValidation(cause);
        }

        if (ClassUtils.isInstanceOf(JWT_EXCEPTION, cause)) {
            return OAuth2ErrorType.MALFORMED_TOKEN;
        }

        if (ClassUtils.isInstanceOf(BAD_CREDENTIALS_EXCEPTION, cause)) {
            return OAuth2ErrorType.BAD_CREDENTIALS;
        }

        if (ClassUtils.isInstanceOf(INSUFFICIENT_AUTHENTICATION_EXCEPTION, cause)) {
            return OAuth2ErrorType.INSUFFICIENT_AUTHENTICATION;
        }

        if (ClassUtils.isInstanceOf(CLIENT_AUTHORIZATION_REQUIRED_EXCEPTION, cause)) {
            return OAuth2ErrorType.AUTHORIZATION_REQUIRED;
        }

        if (ClassUtils.isInstanceOf(CLIENT_AUTHORIZATION_EXCEPTION, cause)) {
            return OAuth2ErrorType.CLIENT_AUTHORIZATION_FAILED;
        }

        if (ClassUtils.isInstanceOf(OAUTH2_AUTHENTICATION_EXCEPTION, cause)
                || ClassUtils.isInstanceOf(OAUTH2_AUTHORIZATION_EXCEPTION, cause)) {
            return classifyByErrorCode(cause);
        }

        return OAuth2ErrorType.GENERIC;
    }

    @NonNull
    private static OAuth2ErrorType classifyJwtValidation(@NonNull Throwable cause) {
        AtomicReference<OAuth2ErrorType> result = new AtomicReference<>(OAuth2ErrorType.INVALID_TOKEN);

        ReflectionUtils.invokeMethod(cause, "getErrors", errors -> {
            if (!(errors instanceof Collection<?> c)) {
                return;
            }

            for (Object error : c) {
                if (result.get() == OAuth2ErrorType.TOKEN_EXPIRED) {
                    break;
                }

                ReflectionUtils.invokeMethod(error, "getErrorCode", code -> {
                    if (!"invalid_token".equals(code)) {
                        return;
                    }

                    ReflectionUtils.invokeMethod(error, "getDescription", desc -> {
                        if (desc instanceof String s && s.toLowerCase(Locale.ROOT).contains("expired")) {
                            result.set(OAuth2ErrorType.TOKEN_EXPIRED);
                        }
                    });
                });
            }
        });

        return result.get();
    }

    @NonNull
    private static OAuth2ErrorType classifyByErrorCode(@NonNull Throwable cause) {
        AtomicReference<OAuth2ErrorType> result = new AtomicReference<>(OAuth2ErrorType.GENERIC);

        ReflectionUtils.invokeMethod(cause, "getError", error -> {
            if (error == null) {
                return;
            }

            ReflectionUtils.invokeMethod(error, "getErrorCode", code -> {
                if (code instanceof String s) {
                    result.set(classifyByRfc6749Code(s));
                }
            });
        });

        return result.get();
    }

    /**
     * Маппинг стандартных и vendor-extension error codes.
     *
     * @see <a href="https://www.rfc-editor.org/rfc/rfc6749#section-5.2">RFC 6749 §5.2 — token endpoint errors</a>
     * @see <a href="https://www.rfc-editor.org/rfc/rfc6749#section-4.1.2.1">RFC 6749 §4.1.2.1 — authorization endpoint errors</a>
     * @see <a href="https://www.rfc-editor.org/rfc/rfc6750#section-3.1">RFC 6750 §3.1 — Bearer token errors</a>
     * @see <a href="https://www.rfc-editor.org/rfc/rfc9449#section-7.1">RFC 9449 §7.1 — DPoP errors</a>
     */
    @NonNull
    private static OAuth2ErrorType classifyByRfc6749Code(@Nullable String code) {
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
    private Map<String, String> buildDiagnosticMetadata(@NonNull Throwable cause,
                                                        @NonNull OAuth2ErrorType type) {
        DiagnosticMetadataBuilder builder = diagnosticMetadataFor(cause);

        String sanitizedMessage = OAuth2DiagnosticSanitizer.sanitize(cause.getMessage(), MAX_CAUSE_MESSAGE_LENGTH);
        if (sanitizedMessage != null) {
            builder.put("causeMessage", sanitizedMessage);
        }

        enrichBearerChallenge(cause, builder);

        ReflectionUtils.invokeMethod(cause, "getClientRegistrationId", id -> {
            if (id instanceof String s && StringUtils.hasText(s)) {
                builder.put("clientRegistrationId", s);
            }
        });

        if (type.requiresNestedCause()) {
            enrichWithNestedCause(cause, builder);
        }

        return builder.withStackHint().build();
    }

    /**
     * Извлекает Bearer-challenge поля из {@code OAuth2Error}, доступного через
     * рефлексивный вызов {@code getError()}. Имена полей соответствуют RFC 6750 §3
     * ({@code error}, {@code error_description}, {@code scope}). Добавлено
     * {@code missingScopes} как алиас для совместимости со старой metadata-схемой.
     */
    private static void enrichBearerChallenge(@NonNull Throwable cause,
                                              @NonNull DiagnosticMetadataBuilder builder) {
        ReflectionUtils.invokeMethod(cause, "getError", error -> {
            if (error == null) {
                return;
            }

            ReflectionUtils.invokeMethod(error, "getErrorCode", code -> {
                if (code instanceof String s && StringUtils.hasText(s)) {
                    builder.put("oauth2ErrorCode", s);
                    builder.put("bearerError", s);
                }
            });

            ReflectionUtils.invokeMethod(error, "getDescription", desc -> {
                if (desc instanceof String s && StringUtils.hasText(s)) {
                    String safe = OAuth2DiagnosticSanitizer.sanitize(s, MAX_BEARER_DESCRIPTION_LENGTH);
                    if (safe != null) {
                        builder.put("bearerErrorDescription", safe);
                    }
                }
            });

            // BearerTokenError.getScope() — присутствует только на subclass'ах OAuth2Error.
            ReflectionUtils.invokeMethod(error, "getScope", scope -> {
                if (scope instanceof String s && StringUtils.hasText(s)) {
                    builder.put("bearerScope", s);
                    builder.put("missingScopes", s);
                }
            });
        });
    }

    private static void enrichWithNestedCause(@NonNull Throwable cause,
                                              @NonNull DiagnosticMetadataBuilder builder) {
        int maxDepth = CauseMetadataEnricher.DIAGNOSTIC_CAUSE_CHAIN_DEPTH;
        Throwable nested = CauseChainNavigator.findDeepestCause(cause, maxDepth);

        if (nested == cause) {
            if (isDepthExceeded(cause, maxDepth)) {
                builder.put("nestedCauseDepthExceeded", "true");
            }
            return;
        }

        String nestedClassName = nested.getClass().getName();
        builder.put("nestedCauseClass", nestedClassName);

        String nestedType = NESTED_CAUSE_TYPE_MAP.get(nestedClassName);
        if (nestedType != null) {
            builder.put("nestedCauseType", nestedType);
        }

        if (NETWORK_TRANSIENT_CLASSES.contains(nestedClassName)) {
            String safe = OAuth2DiagnosticSanitizer.sanitize(nested.getMessage(), MAX_CAUSE_MESSAGE_LENGTH);
            if (safe != null) {
                builder.put("nestedCauseMessage", safe);
            }
        }
    }

    private static boolean isDepthExceeded(@NonNull Throwable cause, int maxDepth) {
        if (cause.getCause() == null || cause.getCause() == cause) {
            return false;
        }

        Throwable current = cause;
        int depth = 0;
        while (current != null && depth <= maxDepth) {
            current = current.getCause();
            depth++;
        }

        return current != null;
    }

    enum OAuth2ErrorType {

        /** RFC 6750 §3.1: token expired — клиент может повторить с refreshed token. */
        TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, LogPolicy.WARN, Domain.OAUTH2, Retryability.RETRYABLE,
                "The provided access token has expired. Please obtain a new token and retry."),

        /** RFC 6750 §3.1: invalid_token — подпись не прошла или токен отозван. */
        INVALID_TOKEN(HttpStatus.UNAUTHORIZED, LogPolicy.WARN, Domain.OAUTH2, Retryability.NOT_RETRYABLE,
                "The provided access token is invalid or has been revoked."),

        /** JWT структурно некорректен (не парсится). */
        MALFORMED_TOKEN(HttpStatus.UNAUTHORIZED, LogPolicy.WARN, Domain.OAUTH2, Retryability.NOT_RETRYABLE,
                "The provided token is malformed and cannot be parsed."),

        BAD_CREDENTIALS(HttpStatus.UNAUTHORIZED, LogPolicy.WARN, Domain.SECURITY, Retryability.NOT_RETRYABLE,
                "The provided credentials are invalid."),

        INSUFFICIENT_AUTHENTICATION(HttpStatus.UNAUTHORIZED, LogPolicy.WARN, Domain.SECURITY, Retryability.NOT_RETRYABLE,
                "Full authentication is required to access this resource."),

        /** RFC 6750 §3.1: insufficient_scope. */
        INSUFFICIENT_SCOPE(HttpStatus.FORBIDDEN, LogPolicy.WARN, Domain.OAUTH2, Retryability.NOT_RETRYABLE,
                "The access token does not have sufficient scope to perform this operation."),

        /** RFC 6749 §4.1.2.1: access_denied. */
        ACCESS_DENIED(HttpStatus.FORBIDDEN, LogPolicy.WARN, Domain.OAUTH2, Retryability.NOT_RETRYABLE,
                "Access was denied by the authorization server."),

        /** RFC 6749 §5.2: invalid_grant — authorization grant invalid/expired/revoked. */
        INVALID_GRANT(HttpStatus.BAD_REQUEST, LogPolicy.WARN, Domain.OAUTH2, Retryability.NOT_RETRYABLE,
                "The provided authorization grant is invalid, expired, or revoked."),

        /** RFC 6749 §5.2: invalid_request — malformed token request. */
        INVALID_REQUEST(HttpStatus.BAD_REQUEST, LogPolicy.WARN, Domain.OAUTH2, Retryability.NOT_RETRYABLE,
                "The OAuth2 token request is missing a parameter or is otherwise malformed."),

        /** RFC 6749 §5.2: invalid_scope — requested scope is invalid/unknown. */
        INVALID_SCOPE(HttpStatus.BAD_REQUEST, LogPolicy.WARN, Domain.OAUTH2, Retryability.NOT_RETRYABLE,
                "The requested OAuth2 scope is invalid, unknown, or malformed."),

        /** RFC 6749 §5.2: unauthorized_client — client not authorized for this grant. */
        UNAUTHORIZED_CLIENT(HttpStatus.FORBIDDEN, LogPolicy.ERROR, Domain.OAUTH2, Retryability.NOT_RETRYABLE,
                "The OAuth2 client is not authorized to use the requested grant type."),

        /** RFC 6749 §5.2: unsupported_grant_type / unsupported_response_type. */
        UNSUPPORTED_GRANT_TYPE(HttpStatus.BAD_REQUEST, LogPolicy.ERROR, Domain.OAUTH2, Retryability.NOT_RETRYABLE,
                "The requested OAuth2 grant or response type is not supported by the authorization server."),

        AUTHORIZATION_REQUIRED(HttpStatus.BAD_GATEWAY, LogPolicy.ERROR, Domain.OAUTH2, Retryability.NOT_RETRYABLE,
                "The service was unable to obtain authorization for an upstream dependency."),

        /** ClientAuthorizationException — обычно ошибка конфигурации client credentials. */
        CLIENT_AUTHORIZATION_FAILED(HttpStatus.SERVICE_UNAVAILABLE, LogPolicy.ERROR, Domain.OAUTH2, Retryability.NOT_RETRYABLE,
                "The OAuth2 client failed to obtain authorization. " +
                        "This may indicate a misconfiguration of the client credentials."),

        /** RFC 6749 §5.2: server_error / temporarily_unavailable — транзиентный сбой IdP. */
        AUTHORIZATION_SERVER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, LogPolicy.ERROR, Domain.OAUTH2, Retryability.RETRYABLE,
                "The authorization server is temporarily unavailable. Please try again later."),

        /** RFC 9449 §7.1: invalid_dpop_proof — DPoP proof JWT невалиден. */
        INVALID_DPOP_PROOF(HttpStatus.UNAUTHORIZED, LogPolicy.WARN, Domain.OAUTH2, Retryability.NOT_RETRYABLE,
                "The provided DPoP proof is invalid."),

        /** RFC 9449 §8: use_dpop_nonce — клиент должен повторить с server-issued nonce. */
        USE_DPOP_NONCE(HttpStatus.UNAUTHORIZED, LogPolicy.WARN, Domain.OAUTH2, Retryability.RETRYABLE,
                "Authorization server requires a fresh DPoP nonce. Retry with the provided nonce."),

        /** RFC 8705: mTLS-bound token rejected when mTLS not established. */
        MTLS_REQUIRED(HttpStatus.UNAUTHORIZED, LogPolicy.ERROR, Domain.OAUTH2, Retryability.NOT_RETRYABLE,
                "Mutual TLS is required to use the presented client credentials."),

        GENERIC(HttpStatus.UNAUTHORIZED, LogPolicy.ERROR, Domain.OAUTH2, Retryability.UNSPECIFIED,
                "An unexpected OAuth2 authorization error occurred.");

        private final HttpStatus httpStatus;
        private final LogPolicy logPolicy;
        private final Domain domain;
        private final Retryability retryability;
        private final String message;

        OAuth2ErrorType(@NonNull HttpStatus httpStatus,
                        @NonNull LogPolicy logPolicy,
                        @NonNull Domain domain,
                        @NonNull Retryability retryability,
                        @NonNull String message) {
            this.httpStatus = httpStatus;
            this.logPolicy = logPolicy;
            this.domain = domain;
            this.retryability = retryability;
            this.message = message;
        }

        @NonNull HttpStatus httpStatus()       { return httpStatus; }
        @NonNull LogPolicy  logPolicy()        { return logPolicy; }
        @NonNull String     domain()           { return domain.value; }
        @NonNull Retryability retryability()   { return retryability; }
        @NonNull String     message()          { return message; }

        /** Типы, для которых имеет смысл анализировать nested cause-chain. */
        boolean requiresNestedCause() {
            return this == AUTHORIZATION_SERVER_UNAVAILABLE
                    || this == CLIENT_AUTHORIZATION_FAILED
                    || this == GENERIC;
        }

        private enum Domain {
            OAUTH2("oauth2"),
            SECURITY("security");

            private final String value;

            Domain(@NonNull String value) {
                this.value = Objects.requireNonNull(value);
            }
        }
    }
}
