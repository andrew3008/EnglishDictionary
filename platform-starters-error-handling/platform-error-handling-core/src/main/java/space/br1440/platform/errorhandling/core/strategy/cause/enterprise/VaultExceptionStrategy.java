package space.br1440.platform.errorhandling.core.strategy.cause.enterprise;

import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import space.br1440.platform.errorhandling.core.util.*;
import space.br1440.platform.errorhandling.extension.ErrorClassification;
import space.br1440.platform.errorhandling.extension.LogPolicy;
import space.br1440.platform.errorhandling.extension.Retryability;
import space.br1440.platform.errorhandling.extension.RootCauseResolution;
import space.br1440.platform.errorhandling.extension.base.AbstractRootCauseExceptionStrategy;
import space.br1440.platform.errorhandling.extension.order.RootCauseExceptionStrategyOrder;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.error_detail.ErrorDetail;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;
import space.br1440.platform.errorhandling.model.error_detail.RetryInfoDetail;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.*;

/**
 * Стратегия обработки исключений HashiCorp Vault (Spring Vault) как первопричины бизнес-исключений.
 *
 * <h2>Поддерживаемые исключения</h2>
 * <pre>
 *   org.springframework.vault
 *   ├── VaultException (base)                             → 503  GENERIC
 *   ├── VaultAccessDeniedException                        → 403  ACCESS_DENIED
 *   ├── VaultTokenExpiredException                        → 503  TOKEN_EXPIRED         retryable
 *   │
 *   ├── authentication
 *   │   ├── VaultLoginException                           → 503  AUTH_FAILURE          retryable
 *   │   ├── VaultTokenRenewalException                    → 503  TOKEN_RENEWAL_FAILED  retryable
 *   │   ├── VaultTokenLookupException                     → 503  TOKEN_LOOKUP_FAILED   retryable
 *   │   └── VaultSessionManagerException                  → 503  SESSION_ERROR         retryable
 *   │
 *   └── core
 *       └── SecretNotFoundException                       → 404  SECRET_NOT_FOUND
 *
 *   Cause chain fallback (для VaultException без специфичного подкласса):
 *   ├── cause HTTP 503 + "sealed" в message               → 503  SEALED                retryable
 *   ├── cause HTTP 400 + "lease" в message                → 503  LEASE_EXPIRED         retryable
 *   ├── cause HTTP 429                                    → 429  RATE_LIMITED          retryable
 *   └── cause ConnectException / UnknownHostException     → 503  CONNECTIVITY          retryable
 * </pre>
 *
 * <h2>Семантика HTTP статуса и retryability</h2>
 * <p>Ошибки взаимодействия с Vault — это сбой внутренней зависимости сервиса, а не проблема запроса
 * клиента. Поэтому token/auth/session/lease-ошибки возвращаются клиенту как {@code 503 Service
 * Unavailable} (RFC 9110 §15.6.4) с {@link RetryInfoDetail}: типовой renewal/re-login происходит
 * в фоне ({@code SessionManager.refreshToken}), и повторный вызов через разумный interval имеет
 * высокие шансы на успех. Недопустимо смешивать {@code 503} с {@code NOT_RETRYABLE}: это
 * конфликтующие сигналы для клиента (HTTP семантика 503 подразумевает retry).
 * {@code SECRET_NOT_FOUND} и {@code ACCESS_DENIED} — детерминированные ошибки конфигурации,
 * ретрай их не исправит.
 *
 * <h2>Диагностические метаданные</h2>
 *
 * <p>Поля {@code diagnosticMetadata} (внутренние логи/мониторинг, <b>не</b> клиентский ответ):</p>
 *
 * <h3>Базовые поля (все типы ошибок)</h3>
 * <ul>
 *   <li>{@code exceptionClass} — FQCN исключения ({@code getName()}).</li>
 *   <li>Семантический тип классификации Vault ({@code VaultErrorType.name()}) задаётся только в поле
 *       {@code reason} у {@link ErrorInfoDetail} — в {@code diagnosticMetadata} <b>не дублируется</b>,
 *       чтобы не повторять одно и то же значение в двух местах.</li>
 *   <li>{@code vaultHttpStatus} — HTTP-статус ответа Vault API (если доступен). Семейство
 *       (4xx/5xx) не дублируется отдельным полем: derived-агрегация делается на стороне
 *       Grafana/Loki ({@code label_replace}) без добавления cardinality.</li>
 *   <li>{@code vaultAuthBackend} — backend аутентификации (только из whitelist:
 *       {@code kubernetes}, {@code approle}, {@code token}, {@code cert}, {@code jwt},
 *       {@code ldap}, {@code userpass}, {@code aws}, {@code gcp}, {@code azure}, {@code oidc}).
 *       Нестандартные/кастомные backend'ы не записываются — могут раскрывать
 *       внутреннюю архитектуру. Значение уже в нормализованной форме (lowercase из whitelist)
 *       и пригодно для агрегации напрямую.</li>
 *   <li>{@code vaultOperation} — тип операции: {@code read}, {@code write}, {@code login},
 *       {@code lease_renew}, {@code lease_request}, {@code transit}, {@code pki},
 *       {@code sys}, {@code token_lifecycle}, {@code unwrap}. Отдельное поле
 *       {@code vaultEngine} не публикуется: для {@code transit}/{@code pki}/{@code sys}/{@code lease*}
 *       оно тождественно {@code vaultOperation}, а для {@code read}/{@code write}
 *       честно определить engine из stack-frame невозможно — {@code VaultTemplate.read/write}
 *       обслуживает kv, database, aws, gcp, transit и custom engines, а парсинг
 *       secret path нарушил бы CWE-209.</li>
 *   <li>{@code nestedCauseClass} / {@code nestedCauseType} — FQCN и нормализованная категория
 *       сетевой первопричины ({@code CONNECTION_REFUSED}, {@code UNKNOWN_HOST}, {@code TLS_ERROR},
 *       {@code SOCKET_TIMEOUT}, {@code NETWORK}). Текст сообщения <b>намеренно не включается</b>
 *       (см. CWE-209).</li>
 *   <li>{@code rootCauseClass} — FQCN глубинной причины (если отличается, глубина до 5).
 *       <b>Без message</b> — CWE-209.</li>
 *   <li>{@code rootCauseCategory} — derived категория: {@code HTTP}, {@code NETWORK},
 *       {@code TLS}, {@code TIMEOUT}.</li>
 *   <li>{@code stackHint} — значимые фреймы стектрейса.</li>
 * </ul>
 *
 * <h2>Безопасность (CWE-209 / CWE-312)</h2>
 * <p>Следующие поля <b>намеренно не включаются</b> даже в {@code diagnosticMetadata}:
 * <ul>
 *   <li>{@code causeMessage} / {@code rootCauseMessage} / {@code nestedCauseMessage} — Vault
 *       exception messages содержат endpoint URL, secret path, mount path, token/accessor,
 *       policy names. Сообщения nested network causes ({@link ConnectException},
 *       {@link UnknownHostException}, {@code SSLException}) тоже запрещены: они
 *       обычно содержат {@code host:port} Vault-кластера (lateral movement).</li>
 *   <li>Vault endpoint URL — раскрывает адрес внутреннего Vault-кластера
 *       (может использоваться для lateral movement).</li>
 *   <li>Secret path / mount path — раскрывает структуру хранения секретов.</li>
 *   <li>Token / accessor — учётные данные; даже expired token accessor
 *       может использоваться для enumeration.</li>
 *   <li>Policy names — раскрывают модель авторизации.</li>
 *   <li>Кастомные auth backend names — могут раскрывать внутреннюю архитектуру;
 *       допускаются только стандартные backend'ы из whitelist.</li>
 * </ul>
 */
public class VaultExceptionStrategy extends AbstractRootCauseExceptionStrategy {

    private static final int CAUSE_CHAIN_DEPTH = 5;

    /**
     * Ограничение на глубину сканирования stack-трейса в {@link #enrichWithVaultOperation}.
     * Vault-операция практически всегда находится в верхних 20–30 фреймах между контроллером/
     * сервисом и JDK. Сканирование всего stack-трейса (обычно 100+ фреймов) клонирует массив на
     * каждый вызов и удлиняет hot path без пользы.
     */
    private static final int MAX_VAULT_STACK_SCAN_DEPTH = 40;

    private static final Set<String> AUTH_BACKEND_WHITELIST = Set.of(
            "kubernetes", "approle", "token", "cert", "jwt",
            "ldap", "userpass", "aws", "gcp", "azure", "oidc"
    );

    /**
     * Whitelist FQCN "безопасных" сетевых причин. Сообщения этих исключений
     * <b>не</b> включаются в metadata (CWE-209 — они могут содержать host:port Vault-кластера),
     * публикуются только FQCN и нормализованная категория ({@link #classifyNestedCauseType}).
     *
     * <p>Для подклассов (например, {@code SSLPeerUnverifiedException extends SSLException})
     * используется {@code isAssignableFrom} (см. {@link #isSafeNetworkCause}).
     */
    private static final Set<Class<?>> SAFE_NETWORK_CAUSE_TYPES = Set.of(
            java.net.ConnectException.class,
            java.net.NoRouteToHostException.class,
            java.net.UnknownHostException.class,
            java.net.SocketTimeoutException.class,
            java.net.SocketException.class,
            javax.net.ssl.SSLException.class,
            java.nio.channels.ClosedChannelException.class
    );

    private static final String VAULT_CORE_PREFIX = "org.springframework.vault.core.";
    private static final String VAULT_AUTH_PREFIX = "org.springframework.vault.authentication.";
    private static final String VAULT_LEASE_PREFIX = "org.springframework.vault.core.lease.";

    private static final ClassLoader LOADER = VaultExceptionStrategy.class.getClassLoader();

    @Nullable
    private static final Class<?> VAULT_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.springframework.vault.VaultException", LOADER);

    @Nullable
    private static final Class<?> VAULT_LOGIN_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.springframework.vault.authentication.VaultLoginException", LOADER);

    @Nullable
    private static final Class<?> VAULT_ACCESS_DENIED =
            ReflectionUtils.loadOptionalClass("org.springframework.vault.VaultAccessDeniedException", LOADER);

    @Nullable
    private static final Class<?> VAULT_TOKEN_EXPIRED =
            ReflectionUtils.loadOptionalClass("org.springframework.vault.VaultTokenExpiredException", LOADER);

    @Nullable
    private static final Class<?> VAULT_TOKEN_RENEWAL =
            ReflectionUtils.loadOptionalClass("org.springframework.vault.authentication.VaultTokenRenewalException", LOADER);

    @Nullable
    private static final Class<?> VAULT_TOKEN_LOOKUP =
            ReflectionUtils.loadOptionalClass("org.springframework.vault.authentication.VaultTokenLookupException", LOADER);

    @Nullable
    private static final Class<?> VAULT_SESSION_MANAGER =
            ReflectionUtils.loadOptionalClass("org.springframework.vault.authentication.VaultSessionManagerException", LOADER);

    @Nullable
    private static final Class<?> SECRET_NOT_FOUND =
            ReflectionUtils.loadOptionalClass("org.springframework.vault.core.SecretNotFoundException", LOADER);

    public VaultExceptionStrategy(StackHintExtractor stackHintExtractor) {
        super(RootCauseExceptionStrategyOrder.VAULT, stackHintExtractor);
    }

    /**
     * Все Vault-ошибки как root cause → {@link LogPolicy#ERROR}.
     *
     * <p>Rationale: проблема с секретами, аутентификацией или доступностью Vault —
     * критический инцидент, требующий немедленного расследования (expired token,
     * revoked credentials, sealed Vault, misconfigured policy).
     * {@code ACCESS_DENIED} в production — потенциальный security incident
     * (misconfigured policy, credential escalation attempt).
     */
    @Override
    @NonNull
    public @NotNull LogPolicy logPolicy(@NonNull Exception exception,
                                        @NonNull ErrorEntry errorEntry) {
        return LogPolicy.ERROR;
    }

    @Override
    public boolean supportsCause(@NonNull Throwable cause) {
        return ClassUtils.isInstanceOf(VAULT_EXCEPTION, cause);
    }

    @Override
    @NonNull
    public RootCauseResolution extractCauseDetails(@NonNull Throwable cause) {
        VaultErrorType type = classify(cause);

        List<ErrorDetail> details = new ArrayList<>();
        if (type.retryability() == Retryability.RETRYABLE) {
            details.add(new RetryInfoDetail());
        }

        ErrorInfoDetail info = ErrorInfoDetail.builder()
                .reason(type.name())
                .domain("vault")
                .diagnosticMetadata(buildDiagnosticMetadata(cause, type))
                .build();
        details.add(info);

        return type.toResolution(details);
    }

    @NonNull
    private static VaultErrorType classify(@NonNull Throwable throwable) {
        if (ClassUtils.isInstanceOf(VAULT_ACCESS_DENIED, throwable)) {
            return VaultErrorType.ACCESS_DENIED;
        }

        if (ClassUtils.isInstanceOf(VAULT_TOKEN_EXPIRED, throwable)) {
            return VaultErrorType.TOKEN_EXPIRED;
        }

        if (ClassUtils.isInstanceOf(VAULT_TOKEN_RENEWAL, throwable)) {
            return VaultErrorType.TOKEN_RENEWAL_FAILED;
        }

        if (ClassUtils.isInstanceOf(VAULT_TOKEN_LOOKUP, throwable)) {
            return VaultErrorType.TOKEN_LOOKUP_FAILED;
        }

        if (ClassUtils.isInstanceOf(VAULT_SESSION_MANAGER, throwable)) {
            return VaultErrorType.SESSION_ERROR;
        }

        if (ClassUtils.isInstanceOf(VAULT_LOGIN_EXCEPTION, throwable)) {
            return VaultErrorType.AUTH_FAILURE;
        }

        if (ClassUtils.isInstanceOf(SECRET_NOT_FOUND, throwable)) {
            return VaultErrorType.SECRET_NOT_FOUND;
        }

        return classifyFromCauseChain(throwable);
    }

    /**
     * <p>CWE-209: message inspection используется только для классификации,
     * само сообщение не включается в metadata.
     */
    @NonNull
    private static VaultErrorType classifyFromCauseChain(@NonNull Throwable throwable) {
        int httpStatus = extractCauseChainHttpStatus(throwable);

        if (httpStatus == HttpStatus.TOO_MANY_REQUESTS.value()) {
            return VaultErrorType.RATE_LIMITED;
        }

        if (httpStatus == HttpStatus.SERVICE_UNAVAILABLE.value() && containsHint(throwable, "sealed")) {
            return VaultErrorType.SEALED;
        }

        if (httpStatus == HttpStatus.BAD_REQUEST.value() && containsHint(throwable, "lease")) {
            return VaultErrorType.LEASE_EXPIRED;
        }

        if (CauseChainNavigator.findCause(throwable, ConnectException.class, CAUSE_CHAIN_DEPTH) != null
                || CauseChainNavigator.findCause(throwable, UnknownHostException.class, CAUSE_CHAIN_DEPTH) != null) {
            return VaultErrorType.CONNECTIVITY;
        }

        return VaultErrorType.GENERIC;
    }

    private static int extractCauseChainHttpStatus(@NonNull Throwable throwable) {
        Throwable current = throwable;

        for (int i = 0; i < CAUSE_CHAIN_DEPTH && current != null; i++) {
            int status = ReflectionUtils.extractHttpStatusCode(current);
            if (status > 0) {
                return status;
            }

            Throwable next = current.getCause();
            if (next == current) {
                break;
            }

            current = next;
        }

        return -1;
    }

    private static boolean containsHint(@NonNull Throwable throwable, @NonNull String hint) {
        Throwable current = throwable;

        for (int i = 0; i < CAUSE_CHAIN_DEPTH && current != null; i++) {
            String msg = current.getMessage();
            if (msg != null && msg.toLowerCase(Locale.ROOT).contains(hint)) {
                return true;
            }

            Throwable next = current.getCause();
            if (next == current) {
                break;
            }

            current = next;
        }

        return false;
    }

    /**
     * <p><b>CWE-209:</b> {@code causeMessage} и {@code rootCauseMessage} намеренно
     * не включаются — Vault-сообщения содержат endpoint URL, secret path, mount path,
     * token/accessor, policy names.
     * Вместо текста используются структурные классификаторы:
     * {@code vaultAuthBackend}, {@code vaultHttpStatus}, {@code rootCauseClass}.
     * See: class-level Javadoc § "Безопасность (CWE-209 / CWE-312)"
     */
    @NonNull
    private Map<String, String> buildDiagnosticMetadata(@NonNull Throwable cause,
                                                        @NonNull VaultErrorType type) {
        DiagnosticMetadataBuilder builder = DiagnosticMetadataBuilder.forCause(cause, stackHintExtractor);

        enrichWithVaultContext(cause, builder);
        enrichWithVaultOperation(cause, builder);
        enrichByErrorType(cause, type, builder);
        enrichWithRootCause(cause, builder);

        return builder
                .withStackHint()
                .build();
    }

    private static void enrichWithVaultContext(@NonNull Throwable cause,
                                               @NonNull DiagnosticMetadataBuilder builder) {
        ReflectionUtils.invokeMethod(cause, "getAuthenticationMethod", method -> {
            if (method instanceof String s && StringUtils.hasText(s)) {
                String lower = s.toLowerCase(Locale.ROOT).trim();
                if (AUTH_BACKEND_WHITELIST.contains(lower)) {
                    builder.put("vaultAuthBackend", lower);
                }
            }
        });

        final int[] statusHolder = {-1};
        ReflectionUtils.invokeMethod(cause, "getStatusCode", status -> {
            if (status instanceof Integer i) {
                statusHolder[0] = i;
            } else if (status != null) {
                ReflectionUtils.invokeMethod(status, "value", val -> {
                    if (val instanceof Integer i) {
                        statusHolder[0] = i;
                    }
                });
            }
        });

        if (statusHolder[0] > 0) {
            builder.put("vaultHttpStatus", Integer.toString(statusHolder[0]));
        }
    }

    private static void enrichWithVaultOperation(@NonNull Throwable cause,
                                                 @NonNull DiagnosticMetadataBuilder builder) {
        StackTraceElement[] stack = cause.getStackTrace();
        if (stack == null) {
            return;
        }

        int limit = Math.min(stack.length, MAX_VAULT_STACK_SCAN_DEPTH);
        for (int i = 0; i < limit; i++) {
            StackTraceElement frame = stack[i];
            String className = frame.getClassName();
            if (!isVaultFrame(className)) {
                continue;
            }

            String operation = resolveVaultOperation(frame.getMethodName(), className);
            if (operation != null) {
                builder.put("vaultOperation", operation);
                return;
            }
        }
    }

    private static boolean isVaultFrame(@NonNull String className) {
        return className.startsWith(VAULT_CORE_PREFIX)
                || className.startsWith(VAULT_AUTH_PREFIX)
                || className.startsWith(VAULT_LEASE_PREFIX);
    }

    @Nullable
    private static String resolveVaultOperation(@NonNull String methodName,
                                                @NonNull String className) {
        if (className.startsWith(VAULT_AUTH_PREFIX)) {
            return switch (methodName) {
                case "login", "authenticate", "getVaultToken" -> "login";
                default -> null;
            };
        }

        if (className.startsWith(VAULT_LEASE_PREFIX)) {
            return switch (methodName) {
                case "doRenewLease", "renewLease" -> "lease_renew";
                case "start", "requestRotatingSecret", "requestRenewableSecret" -> "lease_request";
                default -> "lease";
            };
        }

        return switch (methodName) {
            case "read", "list", "get" -> "read";
            case "write", "put", "delete", "patch" -> "write";
            case "unwrap" -> "unwrap";
            case "renew", "revoke" -> "token_lifecycle";
            case "encrypt", "decrypt", "rewrap", "sign", "verify", "hmac" -> "transit";
            case "issueCertificate", "revokeCertificate", "getCrl" -> "pki";
            case "seal", "unseal", "health" -> "sys";
            default -> null;
        };
    }

    private static void enrichByErrorType(@NonNull Throwable cause,
                                          @NonNull VaultErrorType type,
                                          @NonNull DiagnosticMetadataBuilder builder) {
        if (type == VaultErrorType.AUTH_FAILURE
                || type == VaultErrorType.GENERIC
                || type == VaultErrorType.CONNECTIVITY) {
            enrichNestedNetworkCause(cause, builder);
        }

        if (type == VaultErrorType.GENERIC) {
            enrichUnavailableReason(cause, builder);
        }
    }

    private static void enrichNestedNetworkCause(@NonNull Throwable cause,
                                                 @NonNull DiagnosticMetadataBuilder builder) {
        Throwable nested = cause.getCause();
        if (nested == null || nested == cause) {
            return;
        }

        Throwable current = nested;
        for (int i = 0; i < CAUSE_CHAIN_DEPTH && current != null; i++) {
            if (isSafeNetworkCause(current.getClass())) {
                String fqcn = current.getClass().getName();
                builder.put("nestedCauseClass", fqcn);
                builder.put("nestedCauseType", classifyNestedCauseType(fqcn));
                return;
            }

            Throwable next = current.getCause();
            if (next == current) {
                break;
            }

            current = next;
        }

        builder.put("nestedCauseClass", nested.getClass().getName());
    }

    /**
     * Whitelist-проверка "безопасной" сетевой причины: тип {@code causeType} должен быть
     * подклассом одного из {@link #SAFE_NETWORK_CAUSE_TYPES}. Это покрывает стандартные
     * подклассы JDK/JSSE (например, {@code SSLHandshakeException extends SSLException},
     * {@code SSLPeerUnverifiedException extends SSLException}) без хрупких эвристик
     * по имени/префиксу пакета.
     */
    private static boolean isSafeNetworkCause(@NonNull Class<?> causeType) {
        for (Class<?> safe : SAFE_NETWORK_CAUSE_TYPES) {
            if (safe.isAssignableFrom(causeType)) {
                return true;
            }
        }

        return false;
    }

    @NonNull
    private static String classifyNestedCauseType(@NonNull String fqcn) {
        if (fqcn.contains("ConnectException") || fqcn.contains("NoRouteToHost")) {
            return "CONNECTION_REFUSED";
        }

        if (fqcn.contains("UnknownHost")) {
            return "UNKNOWN_HOST";
        }

        if (fqcn.contains("SSL") || fqcn.contains("Certificate") || fqcn.contains("CertPath")) {
            return "TLS_ERROR";
        }

        if (fqcn.contains("SocketTimeout") || fqcn.contains("TimeoutException")) {
            return "SOCKET_TIMEOUT";
        }

        return "NETWORK";
    }

    /**
     * <p>CWE-209: само сообщение не публикуется, используется только
     * для keyword matching ({@code sealed}, {@code standby}).
     */
    private static void enrichUnavailableReason(@NonNull Throwable cause,
                                                @NonNull DiagnosticMetadataBuilder builder) {
        Throwable current = cause;
        for (int i = 0; i < CAUSE_CHAIN_DEPTH && current != null; i++) {
            String msg = current.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase(Locale.ROOT);
                if (lower.contains("sealed")) {
                    builder.put("vaultUnavailableReason", "SEALED");
                    return;
                }

                if (lower.contains("standby")) {
                    builder.put("vaultUnavailableReason", "STANDBY");
                    return;
                }
            }

            Throwable next = current.getCause();
            if (next == current) {
                break;
            }

            current = next;
        }
    }

    /**
     * <p><b>CWE-209:</b> {@code rootCauseMessage} намеренно не включается — Vault root cause
     * messages содержат endpoint URL, secret path, token. Только FQCN класса +
     * derived category ({@code HTTP}, {@code NETWORK}, {@code TLS}, {@code TIMEOUT}).
     */
    private static void enrichWithRootCause(@NonNull Throwable cause,
                                            @NonNull DiagnosticMetadataBuilder builder) {
        Throwable deepest = CauseChainNavigator.findDeepestCause(cause, CAUSE_CHAIN_DEPTH);
        if (deepest == cause) {
            return;
        }

        String fqcn = deepest.getClass().getName();
        builder.put("rootCauseClass", fqcn);
        builder.putIfPresent("rootCauseCategory", resolveRootCauseCategory(fqcn));
    }

    @Nullable
    private static String resolveRootCauseCategory(@NonNull String fqcn) {
        if (fqcn.contains("HttpClientErrorException") ||
                fqcn.contains("HttpServerErrorException") ||
                fqcn.contains("HttpStatusCodeException") ||
                fqcn.contains("RestClientResponseException")) {
            return "HTTP";
        }

        if (fqcn.contains("ConnectException") ||
                fqcn.contains("UnknownHostException") ||
                fqcn.contains("NoRouteToHostException")) {
            return "NETWORK";
        }

        if (fqcn.contains("SSLException") ||
                fqcn.contains("SSLHandshakeException") ||
                fqcn.contains("CertificateException") ||
                fqcn.contains("CertPathValidator")) {
            return "TLS";
        }

        if (fqcn.contains("SocketTimeoutException") ||
                fqcn.contains("TimeoutException") ||
                fqcn.contains("ConnectTimeoutException")) {
            return "TIMEOUT";
        }

        return null;
    }

    private enum VaultErrorType implements ErrorClassification {
        ACCESS_DENIED(
                HttpStatus.FORBIDDEN,
                Retryability.NOT_RETRYABLE,
                "Access to the requested secret or Vault resource is denied. " +
                        "The service does not have sufficient permissions."),

        TOKEN_EXPIRED(
                HttpStatus.SERVICE_UNAVAILABLE,
                Retryability.RETRYABLE,
                "The Vault authentication token has expired. " +
                        "The service requires re-authentication to continue accessing secrets."),

        TOKEN_RENEWAL_FAILED(
                HttpStatus.SERVICE_UNAVAILABLE,
                Retryability.RETRYABLE,
                "The Vault token renewal failed. " +
                        "The service requires re-authentication to restore access to secrets."),

        TOKEN_LOOKUP_FAILED(
                HttpStatus.SERVICE_UNAVAILABLE,
                Retryability.RETRYABLE,
                "The Vault token self-lookup failed. " +
                        "The token may have been revoked or is no longer valid."),

        SESSION_ERROR(
                HttpStatus.SERVICE_UNAVAILABLE,
                Retryability.RETRYABLE,
                "The Vault session manager failed to provide a valid session. " +
                        "This indicates a token lifecycle management issue."),

        AUTH_FAILURE(
                HttpStatus.SERVICE_UNAVAILABLE,
                Retryability.RETRYABLE,
                "The service failed to authenticate with the Vault server. " +
                        "This indicates a deployment or credential configuration issue."),

        SECRET_NOT_FOUND(
                HttpStatus.NOT_FOUND,
                Retryability.NOT_RETRYABLE,
                "The requested secret was not found in the Vault server. " +
                        "Verify the secret path and KV engine configuration."),

        SEALED(
                HttpStatus.SERVICE_UNAVAILABLE,
                Retryability.RETRYABLE,
                "The Vault server is sealed and cannot serve requests. " +
                        "If auto-unseal is configured, the service will recover automatically."),

        LEASE_EXPIRED(
                HttpStatus.SERVICE_UNAVAILABLE,
                Retryability.RETRYABLE,
                "A dynamic secret lease has expired. " +
                        "The service needs to request a new secret from Vault."),

        RATE_LIMITED(
                HttpStatus.TOO_MANY_REQUESTS,
                Retryability.RETRYABLE,
                "The Vault server has rate-limited the request. " +
                        "Retry after a short delay."),

        CONNECTIVITY(
                HttpStatus.SERVICE_UNAVAILABLE,
                Retryability.RETRYABLE,
                "Unable to connect to the Vault server. " +
                        "The server may be temporarily unavailable or unreachable."),

        GENERIC(
                HttpStatus.SERVICE_UNAVAILABLE,
                Retryability.NOT_RETRYABLE,
                "The secrets management service is temporarily unavailable.");

        private final HttpStatus httpStatus;
        private final Retryability retryability;
        private final String message;

        VaultErrorType(@NonNull HttpStatus httpStatus,
                       @NonNull Retryability retryability,
                       @NonNull String message) {
            this.httpStatus = httpStatus;
            this.retryability = retryability;
            this.message = message;
        }

        @NonNull
        public HttpStatus httpStatus() {
            return httpStatus;
        }

        @NonNull
        public Retryability retryability() {
            return retryability;
        }

        @NonNull
        public String message() {
            return message;
        }
    }
}
