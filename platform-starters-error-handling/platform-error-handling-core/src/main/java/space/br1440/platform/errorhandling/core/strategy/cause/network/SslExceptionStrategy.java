package space.br1440.platform.errorhandling.core.strategy.cause.network;

import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import space.br1440.platform.errorhandling.core.util.CauseMetadataEnricher;
import space.br1440.platform.errorhandling.core.util.DiagnosticMetadataBuilder;
import space.br1440.platform.errorhandling.core.util.ReflectionUtils;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
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

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLKeyException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLProtocolException;
import java.security.cert.CertPath;
import java.security.cert.CertPathBuilderException;
import java.security.cert.CertPathValidatorException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Стратегия обработки TLS/SSL-исключений.
 *
 * <p>Закрывает пробел в классификации исключений, возникающих при установке
 * защищённых соединений с upstream-сервисами, внутренними CA и mTLS-эндпоинтами.
 *
 * <h2>Покрытие</h2>
 * <ul>
 *   <li>Стандартные {@code javax.net.ssl.*} и {@code java.security.cert.*} исключения JDK.</li>
 *   <li>Опционально (по наличию в classpath): {@code io.netty.handler.ssl.*} —
 *       {@code NotSslRecordException}, {@code SslHandshakeTimeoutException},
 *       {@code SslClosedEngineException} (используется через рефлексию,
 *       без compile-time зависимости на Netty).</li>
 * </ul>
 *
 * <h2>Наиболее распространённые enterprise-сценарии</h2>
 * <ul>
 *   <li><b>Ротация сертификатов CA</b> — старый trust store не содержит нового корневого
 *       сертификата до перезапуска приложения.</li>
 *   <li><b>Самоподписанные сертификаты</b> в dev/staging без корректного trust store.</li>
 *   <li><b>mTLS</b> — клиентский сертификат нашего сервиса просрочен ({@code CERT_EXPIRED})
 *       или отсутствует, а upstream его требует ({@code MTLS_CLIENT_CERT_REQUIRED}).</li>
 *   <li><b>Несовместимость TLS-версий</b> — upstream требует TLS 1.3, наш
 *       JSSE настроен на TLS 1.2 ({@code PROTOCOL_ERROR}).</li>
 *   <li><b>Revocation</b> — OCSP/CRL отзыв сертификата ({@code CERT_REVOKED}) либо
 *       недоступность OCSP-respondera/CRL ({@code CERT_REVOCATION_UNKNOWN}).</li>
 * </ul>
 *
 * <h2>Разграничение с {@link HttpIntegrationExceptionStrategy}</h2>
 * <p>
 * {@link HttpIntegrationExceptionStrategy} обрабатывает сетевые исключения
 * {@code SocketTimeoutException}, {@code ConnectException} — ошибки после
 * установки TCP-соединения. Данная стратегия специализирована на <b>TLS
 * handshake/record layer</b> — ошибки до или во время установки TLS-сессии,
 * когда TCP уже открыт. Порядок выполнения: {@link RootCauseExceptionStrategyOrder#SSL}
 * раньше generic-fallback'а HTTP.
 *
 * <h2>Ретраябельность</h2>
 * <ul>
 *   <li>{@code HANDSHAKE_FAILURE}, {@code PROTOCOL_ERROR}, {@code GENERIC_SSL} →
 *       {@link Retryability#UNSPECIFIED}: причина может быть как транзиентной
 *       (mid-rotation CA, временный сбой ALPN), так и фатальной (несовместимая
 *       версия). Решение делегируется политике клиента (Resilience4j retry с
 *       классификатором по {@code sslErrorType}).</li>
 *   <li>Все certificate-errors ({@code CERT_*}, включая {@code CERT_REVOCATION_UNKNOWN}) —
 *       {@link Retryability#NOT_RETRYABLE}: транзиентность здесь маскирует PKI-проблемы.</li>
 *   <li>{@code MTLS_CLIENT_CERT_REQUIRED}, {@code MTLS_PEER_NOT_AUTHENTICATED},
 *       {@code PEER_UNVERIFIED}, {@code LOCAL_KEY_ERROR}, {@code NETTY_*} —
 *       {@link Retryability#NOT_RETRYABLE}.</li>
 * </ul>
 *
 * <h2>Поддерживаемые исключения</h2>
 *
 * <pre>
 *   javax.net.ssl.SSLException                    → 503  GENERIC_SSL          UNSPECIFIED
 *   ├── SSLHandshakeException                      → 503  HANDSHAKE_FAILURE   UNSPECIFIED
 *   │   └── alert "certificate_required"           → 401  MTLS_CLIENT_CERT_REQUIRED
 *   ├── SSLKeyException                            → 500  LOCAL_KEY_ERROR     NOT_RETRYABLE
 *   ├── SSLPeerUnverifiedException                 → 503  PEER_UNVERIFIED     NOT_RETRYABLE
 *   │   └── msg "peer not authenticated"           →     MTLS_PEER_NOT_AUTHENTICATED
 *   └── SSLProtocolException                       → 503  PROTOCOL_ERROR      UNSPECIFIED
 *
 *   java.security.cert.CertificateException        → 503  CERT_INVALID        NOT_RETRYABLE
 *   ├── CertificateExpiredException                → 503  CERT_EXPIRED        NOT_RETRYABLE
 *   └── CertificateNotYetValidException            → 503  CERT_NOT_YET_VALID  NOT_RETRYABLE
 *
 *   java.security.cert.CertPathBuilderException    → 503  CERT_PATH_BUILD_FAILED      NOT_RETRYABLE
 *   java.security.cert.CertPathValidatorException  → 503  CERT_PATH_VALIDATION_FAILED NOT_RETRYABLE
 *     ├── reason=REVOKED                           →     CERT_REVOKED                NOT_RETRYABLE
 *     └── reason=UNDETERMINED_REVOCATION_STATUS    →     CERT_REVOCATION_UNKNOWN     NOT_RETRYABLE
 *
 *   io.netty.handler.ssl.NotSslRecordException     → 503  NETTY_NOT_SSL_RECORD     NOT_RETRYABLE
 *   io.netty.handler.ssl.SslHandshakeTimeoutEx*    → 504  NETTY_HANDSHAKE_TIMEOUT  NOT_RETRYABLE
 *   io.netty.handler.ssl.SslClosedEngineException  → 503  NETTY_CLOSED_ENGINE      NOT_RETRYABLE
 * </pre>
 *
 * <h2>Диагностические метаданные</h2>
 *
 * <p>Поля {@code diagnosticMetadata} (внутренние логи/мониторинг, <b>не</b> клиентский ответ):</p>
 * <ul>
 *   <li>{@code exceptionClass} — FQCN исключения.</li>
 *   <li>{@code sslErrorType} — классификация TLS-ошибки.</li>
 *   <li>{@code tlsAlertDescription} — RFC TLS alert (whitelist) для inbound/outbound alerts.</li>
 *   <li>{@code tlsAlertDirection} — {@code inbound} (received) либо {@code outbound} (sent).</li>
 *   <li>{@code certValidityDetail} — дата NotAfter/NotBefore из message
 *       {@code CertificateExpiredException}/{@code CertificateNotYetValidException}.</li>
 *   <li>{@code certNotAfterEpochSec}, {@code certNotBeforeEpochSec},
 *       {@code certSigAlgName} — структурные поля сертификата по {@code certPath[index]}.</li>
 *   <li>{@code certPathValidationReason} — причина из {@code CertPathValidatorException.Reason}.</li>
 *   <li>{@code certPathIndex}, {@code certPathType}, {@code certChainDepth} — координаты в цепочке.</li>
 *   <li>{@code tlsVersion} — версия TLS-протокола (TLSv1.2/TLSv1.3) если присутствует в message.</li>
 *   <li>{@code handshakeCauseClass} — FQCN причины handshake failure.</li>
 *   <li>{@code rootCauseClass} — FQCN глубинной причины. <b>Без message</b> — CWE-209.</li>
 *   <li>{@code stackHint} — значимые фреймы стектрейса.</li>
 * </ul>
 *
 * <h2>Безопасность (CWE-209/CWE-200)</h2>
 * <p>Намеренно <b>не включаются</b>:
 * <ul>
 *   <li>{@code causeMessage} и {@code rootCauseMessage} — содержат CN, SAN,
 *       серийный номер, fingerprint, hostname upstream'а, путь к keystore.</li>
 *   <li>Issuer/Subject DN, fingerprint, public key — раскрывают идентичность peer'а.</li>
 *   <li>Cipher suite — может помочь в атаке на протокол; намеренно опускаем.</li>
 *   <li>Конкретный hostname/IP upstream'а — раскрывают сетевую топологию.</li>
 * </ul>
 * <p>Разрешённые protocol-level поля: {@code tlsAlertDescription} (RFC enum, whitelist),
 * {@code tlsVersion} (только version identifier), {@code certValidityDetail} (даты validity
 * из стандартного JDK message), {@code certNotAfterEpochSec}/{@code certNotBeforeEpochSec}
 * (epoch seconds — числовые, без идентичности), {@code certSigAlgName} (имя алгоритма подписи).
 */
public class SslExceptionStrategy extends AbstractRootCauseExceptionStrategy {

    private static final int MAX_VALIDITY_LENGTH = 100;

    /** Предельная глубина просмотра внутренних связей (handshakeCause / certPath fallback). */
    private static final int CAUSE_CHAIN_DEPTH = CauseMetadataEnricher.DIAGNOSTIC_CAUSE_CHAIN_DEPTH;

    /**
     * TLS alert description в текстовых сообщениях JSSE.
     * Покрывает оба формата:
     * <pre>
     *   "Received fatal alert: certificate_expired"   ← inbound (от удалённой стороны)
     *   "Sending fatal alert: bad_certificate"        ← outbound (мы отвергаем peer'а)
     * </pre>
     */
    private static final Pattern TLS_ALERT_PATTERN =
            Pattern.compile("(Received|Sending)\\s+fatal\\s+alert:\\s*(\\w+)", Pattern.CASE_INSENSITIVE);

    /**
     * Whitelist разрешённых alert names согласно
     * RFC 5246 §7.2.2 (TLS 1.0–1.2) и RFC 8446 §6.2 (TLS 1.3).
     * Записываем строго фиксированный набор — иначе нарушитель может через подмену
     * сообщений исключения протолкнуть произвольный токен в индексы Loki/ELK.
     */
    private static final Set<String> ALLOWED_ALERTS = Set.of(
            // Общие (RFC 5246 §7.2.2)
            "close_notify",
            "unexpected_message",
            "bad_record_mac",
            "decryption_failed",
            "record_overflow",
            "decompression_failure",
            "handshake_failure",
            "no_certificate",
            "bad_certificate",
            "unsupported_certificate",
            "certificate_revoked",
            "certificate_expired",
            "certificate_unknown",
            "illegal_parameter",
            "unknown_ca",
            "access_denied",
            "decode_error",
            "decrypt_error",
            "export_restriction",
            "protocol_version",
            "insufficient_security",
            "internal_error",
            "user_canceled",
            "no_renegotiation",
            "unsupported_extension",
            // TLS 1.3 (RFC 8446 §6.2)
            "missing_extension",
            "unrecognized_name",
            "bad_certificate_status_response",
            "bad_certificate_hash_value",
            "unknown_psk_identity",
            "certificate_required",
            "no_application_protocol",
            "inappropriate_fallback"
    );

    /**
     * Версия TLS-протокола в текстовых сообщениях.
     * Намеренно используется word boundary — иначе паттерн срабатывает
     * на классах вида {@code TLSv1Test}.
     */
    private static final Pattern TLS_VERSION_PATTERN =
            Pattern.compile("\\b(TLSv1(?:\\.[0-3])?|SSLv[23])\\b", Pattern.CASE_INSENSITIVE);

    /**
     * mTLS — peer (наш клиент или upstream, в зависимости от роли) не предоставил сертификат.
     * Подсветка через keyword необходима, т.к. JDK кидает обычный
     * {@link SSLPeerUnverifiedException} с разной формулировкой message.
     */
    private static final Pattern MTLS_PEER_NOT_AUTHENTICATED_PATTERN =
            Pattern.compile("peer\\s+not\\s+authenticated", Pattern.CASE_INSENSITIVE);

    // ── Опциональные классы Netty SSL (загружаются по classpath) ───────────────

    @Nullable
    private static final Class<?> NETTY_NOT_SSL_RECORD =
            ReflectionUtils.loadOptionalClass("io.netty.handler.ssl.NotSslRecordException", null);

    @Nullable
    private static final Class<?> NETTY_HANDSHAKE_TIMEOUT =
            ReflectionUtils.loadOptionalClass("io.netty.handler.ssl.SslHandshakeTimeoutException", null);

    @Nullable
    private static final Class<?> NETTY_CLOSED_ENGINE =
            ReflectionUtils.loadOptionalClass("io.netty.handler.ssl.SslClosedEngineException", null);

    public SslExceptionStrategy(StackHintExtractor stackHintExtractor) {
        super(RootCauseExceptionStrategyOrder.SSL, stackHintExtractor);
    }

    /**
     * Все TLS/SSL ошибки → {@link LogPolicy#ERROR}.
     *
     * <p>Несмотря на то, что они могут быть «ожидаемыми» (ротация сертификатов),
     * каждая SSL-ошибка требует немедленного вмешательства: пользователи видят
     * 503, upstream недоступен. {@code WARN} маскировал бы production-инциденты.
     */
    @Override
    @NonNull
    public @NotNull LogPolicy logPolicy(@NonNull Exception exception,
                                        @NonNull ErrorEntry errorEntry) {
        return LogPolicy.ERROR;
    }

    @Override
    public boolean supportsCause(@NonNull Throwable cause) {
        return isSslException(cause);
    }

    @Override
    @NonNull
    public RootCauseResolution extractCauseDetails(@NonNull Throwable cause) {
        SslErrorType type = classify(cause);

        Map<String, String> diagnosticMetadata = buildDiagnosticMetadata(cause, type);

        ErrorInfoDetail info = ErrorInfoDetail.builder()
                .reason(type.name())
                .domain("tls")
                .diagnosticMetadata(diagnosticMetadata)
                .build();

        List<ErrorDetail> details = new ArrayList<>();
        details.add(info);
        if (type.retryability() == Retryability.RETRYABLE) {
            // На текущий момент ни один SslErrorType не помечен как RETRYABLE,
            // но контракт сохраняем для расширений (UNSPECIFIED — намеренно без RetryInfoDetail).
            details.add(new RetryInfoDetail());
        }

        return type.toResolution(details);
    }

    @NonNull
    private static SslErrorType classify(@NonNull Throwable throwable) {
        // 1) Netty-специфика — проверяем первой, т.к. Netty-классы наследуют javax.net.ssl.SSLException
        if (NETTY_NOT_SSL_RECORD != null && NETTY_NOT_SSL_RECORD.isInstance(throwable)) {
            return SslErrorType.NETTY_NOT_SSL_RECORD;
        }
        if (NETTY_HANDSHAKE_TIMEOUT != null && NETTY_HANDSHAKE_TIMEOUT.isInstance(throwable)) {
            return SslErrorType.NETTY_HANDSHAKE_TIMEOUT;
        }
        if (NETTY_CLOSED_ENGINE != null && NETTY_CLOSED_ENGINE.isInstance(throwable)) {
            return SslErrorType.NETTY_CLOSED_ENGINE;
        }

        // 2) Стандартные JDK-классы javax.net.ssl и java.security.cert
        return switch (throwable) {
            // javax.net.ssl.* — специфичные перед родителем SSLException
            case SSLHandshakeException he -> classifyHandshake(he);
            case SSLKeyException ignored -> SslErrorType.LOCAL_KEY_ERROR;
            case SSLPeerUnverifiedException pu -> classifyPeerUnverified(pu);
            case SSLProtocolException ignored -> SslErrorType.PROTOCOL_ERROR;
            case SSLException ignored -> SslErrorType.GENERIC_SSL;

            // java.security.cert.* — специфичные перед родителем CertificateException
            case CertificateExpiredException ignored -> SslErrorType.CERT_EXPIRED;
            case CertificateNotYetValidException ignored -> SslErrorType.CERT_NOT_YET_VALID;
            case CertPathBuilderException ignored -> SslErrorType.CERT_PATH_BUILD_FAILED;
            case CertPathValidatorException cpve -> classifyCertPathValidation(cpve);
            case CertificateException ignored -> SslErrorType.CERT_INVALID;

            default -> SslErrorType.GENERIC_SSL;
        };
    }

    /**
     * Если в message handshake присутствует TLS alert {@code certificate_required}
     * (RFC 8446 §6.2 — server требует client-cert, клиент его не прислал) —
     * выделяем подтип {@code MTLS_CLIENT_CERT_REQUIRED}.
     */
    @NonNull
    private static SslErrorType classifyHandshake(@NonNull SSLHandshakeException he) {
        String alert = extractAlertName(he.getMessage());
        if ("certificate_required".equals(alert)) {
            return SslErrorType.MTLS_CLIENT_CERT_REQUIRED;
        }
        return SslErrorType.HANDSHAKE_FAILURE;
    }

    /**
     * Различает generic {@code PEER_UNVERIFIED} и mTLS-сценарий «peer not authenticated»
     * (один из самых частых сигналов отсутствия client-cert в mTLS).
     */
    @NonNull
    private static SslErrorType classifyPeerUnverified(@NonNull SSLPeerUnverifiedException pu) {
        String msg = pu.getMessage();
        if (msg != null && MTLS_PEER_NOT_AUTHENTICATED_PATTERN.matcher(msg).find()) {
            return SslErrorType.MTLS_PEER_NOT_AUTHENTICATED;
        }
        return SslErrorType.PEER_UNVERIFIED;
    }

    /**
     * Уточняет {@code CertPathValidatorException} по {@link CertPathValidatorException.Reason}:
     * REVOKED → {@code CERT_REVOKED}, UNDETERMINED_REVOCATION_STATUS → {@code CERT_REVOCATION_UNKNOWN},
     * остальные — {@code CERT_PATH_VALIDATION_FAILED}.
     *
     * <p><b>Политика:</b> {@code UNDETERMINED_REVOCATION_STATUS} помечается
     * {@link Retryability#NOT_RETRYABLE}. Это сознательный выбор: транзиентная
     * недоступность OCSP-respondera/CRL под {@code RETRYABLE} маскирует
     * реальные PKI-проблемы (отсутствие fail-open политики, misconfigured trust store).
     */
    @NonNull
    private static SslErrorType classifyCertPathValidation(@NonNull CertPathValidatorException cpve) {
        CertPathValidatorException.Reason reason = cpve.getReason();
        if (reason == null) {
            return SslErrorType.CERT_PATH_VALIDATION_FAILED;
        }
        // BasicReason — единственный enum-производитель в JDK; имена сравниваем как String,
        // чтобы не зависеть от того, расширит ли провайдер собственным enum.
        String name = reason.toString();
        if ("REVOKED".equals(name)) {
            return SslErrorType.CERT_REVOKED;
        }
        if ("UNDETERMINED_REVOCATION_STATUS".equals(name)) {
            return SslErrorType.CERT_REVOCATION_UNKNOWN;
        }
        return SslErrorType.CERT_PATH_VALIDATION_FAILED;
    }

    private static boolean isSslException(@NonNull Throwable throwable) {
        if (throwable instanceof SSLException
                || throwable instanceof CertPathBuilderException
                || throwable instanceof CertPathValidatorException
                || throwable instanceof CertificateException) {
            return true;
        }
        // Netty-классы наследуют SSLException, ловятся выше; defensive-проверка
        // на случай иерархических изменений библиотеки.
        return (NETTY_NOT_SSL_RECORD != null && NETTY_NOT_SSL_RECORD.isInstance(throwable))
                || (NETTY_HANDSHAKE_TIMEOUT != null && NETTY_HANDSHAKE_TIMEOUT.isInstance(throwable))
                || (NETTY_CLOSED_ENGINE != null && NETTY_CLOSED_ENGINE.isInstance(throwable));
    }

    /**
     * Собирает расширенные диагностические метаданные для TLS/SSL-ошибки.
     *
     * <p><b>CWE-209:</b> {@code causeMessage} и {@code rootCauseMessage} намеренно
     * не включаются — SSL-сообщения содержат CN, SAN, серийные номера, hostname
     * upstream'а. Вместо текста — структурные классификаторы:
     * {@code sslErrorType}, {@code handshakeCauseClass}, {@code rootCauseClass}.
     */
    @NonNull
    private Map<String, String> buildDiagnosticMetadata(@NonNull Throwable cause,
                                                        @NonNull SslErrorType type) {
        DiagnosticMetadataBuilder builder = diagnosticMetadataFor(cause);
        builder.put("sslErrorType", type.name());

        if (cause instanceof SSLHandshakeException) {
            enrichWithHandshakeContext(cause, builder);
        }

        if (cause instanceof SSLPeerUnverifiedException) {
            // Извлечение alert direction для PEER_UNVERIFIED — некоторые JSSE-реализации
            // вкладывают alert и в сообщение peer-unverified.
            enrichWithAlertDescription(cause, builder);
        }

        if (cause instanceof CertPathValidatorException cpve) {
            enrichWithCertPathContext(cpve, builder);
        }

        if (cause instanceof CertPathBuilderException cpbe) {
            builder.put("certPathBuildFailed", "true");
            enrichCertPathBuilderFallback(cpbe, builder);
        }

        if (cause instanceof CertificateExpiredException
                || cause instanceof CertificateNotYetValidException) {
            enrichWithCertValidityDetail(cause, builder);
        }

        // tlsVersion извлекаем из любого SSL-исключения, если паттерн совпал —
        // версия — безопасный protocol-level identifier (не cipher и не hostname).
        enrichWithTlsVersion(cause, builder);

        // Глубинный класс — без message (CWE-209): дополнительный сигнал «что лежит на дне».
        builder.withRootCauseClassOnly(CAUSE_CHAIN_DEPTH);

        return builder.withStackHint().build();
    }

    /**
     * Извлекает структурный контекст из {@code CertPathValidatorException}:
     * reason, индекс проблемного сертификата в цепочке, тип цепочки, её глубину,
     * а также — если индекс валиден — {@code notBefore/notAfter/sigAlgName}
     * проблемного сертификата (структурные числовые/безопасные поля).
     */
    private static void enrichWithCertPathContext(@NonNull CertPathValidatorException cpve,
                                                  @NonNull DiagnosticMetadataBuilder builder) {
        CertPathValidatorException.Reason reason = cpve.getReason();
        if (reason != null) {
            builder.put("certPathValidationReason", reason.toString());
        }

        int index = cpve.getIndex();
        if (index >= 0) {
            builder.put("certPathIndex", Integer.toString(index));
        }

        CertPath certPath = cpve.getCertPath();
        if (certPath == null) {
            return;
        }

        List<? extends Certificate> certificates = certPath.getCertificates();
        if (certificates != null) {
            builder.put("certChainDepth", String.valueOf(certificates.size()));
            builder.put("certPathType", certPath.getType());

            if (index >= 0 && index < certificates.size()
                    && certificates.get(index) instanceof X509Certificate cert) {
                enrichWithX509Details(cert, builder);
            }
        }
    }

    /**
     * Безопасные структурные поля X.509-сертификата:
     * <ul>
     *   <li>{@code certNotBeforeEpochSec} / {@code certNotAfterEpochSec} — числовые epoch
     *       seconds, не раскрывают идентичность.</li>
     *   <li>{@code certSigAlgName} — имя алгоритма подписи (OID/название), не содержит
     *       секретов и помогает диагностировать crypto-ошибки (deprecated SHA1, etc.).</li>
     * </ul>
     * <p><b>Не включаются:</b> Subject DN, Issuer DN, serial number, public key — раскрывают
     * идентичность peer'а и облегчают enumeration атаки.
     */
    private static void enrichWithX509Details(@NonNull X509Certificate cert,
                                              @NonNull DiagnosticMetadataBuilder builder) {
        try {
            if (cert.getNotBefore() != null) {
                builder.put("certNotBeforeEpochSec",
                        Long.toString(cert.getNotBefore().toInstant().getEpochSecond()));
            }
            if (cert.getNotAfter() != null) {
                builder.put("certNotAfterEpochSec",
                        Long.toString(cert.getNotAfter().toInstant().getEpochSecond()));
            }
            builder.putIfPresent("certSigAlgName", cert.getSigAlgName());
        } catch (Exception ignored) {
            // Любая ошибка чтения сертификата (corrupted DER, провайдер-специфичный)
            // не должна ломать обработку основной ошибки.
        }
    }

    /**
     * Для {@code SSLHandshakeException} извлекает FQCN непосредственной причины
     * handshake failure плюс TLS alert (если присутствует в message).
     *
     * <p>Типичные cause chains:
     * <pre>
     *   SSLHandshakeException
     *   └── sun.security.validator.ValidatorException    (certificate validation)
     *       └── sun.security.provider.certpath.SunCertPathBuilderException
     * </pre>
     */
    private static void enrichWithHandshakeContext(@NonNull Throwable cause,
                                                   @NonNull DiagnosticMetadataBuilder builder) {
        Throwable handshakeCause = cause.getCause();
        if (handshakeCause != null && handshakeCause != cause) {
            builder.put("handshakeCauseClass", handshakeCause.getClass().getName());
        }

        enrichWithAlertDescription(cause, builder);
    }

    /**
     * Извлекает alert-name из message, направление (inbound/outbound), записывает в metadata.
     * Whitelist гарантирует, что в diagnosticMetadata не попадёт произвольный токен из
     * подменённого сообщения исключения.
     */
    private static void enrichWithAlertDescription(@NonNull Throwable cause,
                                                   @NonNull DiagnosticMetadataBuilder builder) {
        String msg = cause.getMessage();
        if (msg == null) {
            return;
        }
        Matcher m = TLS_ALERT_PATTERN.matcher(msg);
        if (!m.find()) {
            return;
        }

        String direction = m.group(1).toLowerCase(Locale.ROOT);
        String alert = m.group(2).toLowerCase(Locale.ROOT);
        if (!ALLOWED_ALERTS.contains(alert)) {
            return;
        }

        builder.put("tlsAlertDescription", alert);
        builder.put("tlsAlertDirection", "received".equals(direction) ? "inbound" : "outbound");
    }

    @Nullable
    private static String extractAlertName(@Nullable String msg) {
        if (msg == null) {
            return null;
        }
        Matcher m = TLS_ALERT_PATTERN.matcher(msg);
        if (!m.find()) {
            return null;
        }
        String alert = m.group(2).toLowerCase(Locale.ROOT);
        return ALLOWED_ALERTS.contains(alert) ? alert : null;
    }

    /**
     * Для {@code CertificateExpiredException}/{@code CertificateNotYetValidException}
     * извлекает {@code certValidityDetail} из message — содержит NotAfter/NotBefore дату.
     * CN/SAN/issuer не включаются.
     */
    private static void enrichWithCertValidityDetail(@NonNull Throwable cause,
                                                     @NonNull DiagnosticMetadataBuilder builder) {
        String causeMessage = cause.getMessage();
        builder.putTruncatedIfPresent("certValidityDetail", causeMessage, MAX_VALIDITY_LENGTH);
    }

    /**
     * Для {@code CertPathBuilderException} — проходит по cause chain и, если находит
     * {@code CertPathValidatorException}, переиспользует enrichment.
     */
    private static void enrichCertPathBuilderFallback(@NonNull CertPathBuilderException cpbe,
                                                      @NonNull DiagnosticMetadataBuilder builder) {
        Throwable current = cpbe.getCause();

        for (int i = 0; i < CAUSE_CHAIN_DEPTH && current != null && current != cpbe; i++) {
            if (current instanceof CertPathValidatorException cpve) {
                enrichWithCertPathContext(cpve, builder);
                return;
            }

            current = current.getCause();
        }
    }

    /**
     * Извлекает версию TLS-протокола из message исключения.
     * Безопасно: только version identifier, не cipher suite и не hostname.
     */
    private static void enrichWithTlsVersion(@NonNull Throwable cause,
                                             @NonNull DiagnosticMetadataBuilder builder) {
        String msg = cause.getMessage();
        if (msg == null) {
            return;
        }

        Matcher m = TLS_VERSION_PATTERN.matcher(msg);
        if (m.find()) {
            builder.put("tlsVersion", m.group(1));
        }
    }

    /**
     * Внутренний классификатор TLS/SSL-ошибок.
     *
     * <p>Каждое значение хранит {@code httpStatus}, {@code retryability} и {@code message}
     * через контракт {@link ErrorClassification}.
     */
    private enum SslErrorType implements ErrorClassification {

        /**
         * TLS handshake failure (общий сигнал). Может быть как транзиентной
         * проблемой (mid-rotation CA, временный сбой ALPN), так и фатальной
         * (несовместимая версия). Решение делегируется политике клиента.
         */
        HANDSHAKE_FAILURE(
                HttpStatus.SERVICE_UNAVAILABLE,
                Retryability.UNSPECIFIED,
                "A TLS/SSL handshake failure occurred while connecting to an upstream service. " +
                        "The server certificate may be invalid, expired, or not trusted."),

        /**
         * mTLS — server требует клиентский сертификат, клиент его не прислал
         * (TLS 1.3 alert {@code certificate_required}, RFC 8446 §6.2).
         * 401 — это конфигурационная проблема на стороне клиента (нет client-cert).
         */
        MTLS_CLIENT_CERT_REQUIRED(
                HttpStatus.UNAUTHORIZED,
                Retryability.NOT_RETRYABLE,
                "The upstream service requires a client certificate (mTLS), " +
                        "but no client certificate was presented. Verify the client keystore configuration."),

        PEER_UNVERIFIED(
                HttpStatus.SERVICE_UNAVAILABLE,
                Retryability.NOT_RETRYABLE,
                "The identity of the upstream service could not be verified. " +
                        "The server certificate or the configured hostname is invalid."),

        /**
         * mTLS — peer не аутентифицировался (нет client-cert при mTLS-engine,
         * либо handshake не достиг стадии аутентификации).
         */
        MTLS_PEER_NOT_AUTHENTICATED(
                HttpStatus.SERVICE_UNAVAILABLE,
                Retryability.NOT_RETRYABLE,
                "The TLS peer was not authenticated. " +
                        "This typically indicates a missing client certificate during mTLS handshake."),

        PROTOCOL_ERROR(
                HttpStatus.SERVICE_UNAVAILABLE,
                Retryability.UNSPECIFIED,
                "A TLS/SSL protocol error occurred. " +
                        "This may indicate an incompatible TLS version or cipher suite between services."),

        CERT_EXPIRED(
                HttpStatus.SERVICE_UNAVAILABLE,
                Retryability.NOT_RETRYABLE,
                "The TLS/SSL certificate used in the connection has expired. " +
                        "Certificate renewal and trust store update are required."),

        CERT_NOT_YET_VALID(
                HttpStatus.SERVICE_UNAVAILABLE,
                Retryability.NOT_RETRYABLE,
                "The TLS/SSL certificate used in the connection is not yet valid. " +
                        "Verify the system clock synchronization or the certificate validity dates."),

        /** Не удалось <i>построить</i> certification path до доверенного якоря. */
        CERT_PATH_BUILD_FAILED(
                HttpStatus.SERVICE_UNAVAILABLE,
                Retryability.NOT_RETRYABLE,
                "Unable to build a TLS/SSL certificate path to a trusted authority. " +
                        "The issuing certificate authority may not be present in the trust store."),

        /** Удалось построить, но <i>валидация</i> certification path провалилась. */
        CERT_PATH_VALIDATION_FAILED(
                HttpStatus.SERVICE_UNAVAILABLE,
                Retryability.NOT_RETRYABLE,
                "TLS/SSL certificate path validation failed. " +
                        "One of the certificates in the chain is invalid."),

        /** Сертификат отозван удостоверяющим центром (OCSP/CRL: REVOKED). */
        CERT_REVOKED(
                HttpStatus.SERVICE_UNAVAILABLE,
                Retryability.NOT_RETRYABLE,
                "The TLS/SSL certificate has been revoked by the issuing authority. " +
                        "Replace the certificate; do not bypass revocation checks."),

        /**
         * Не удалось определить статус отзыва (OCSP-responder/CRL недоступен).
         * Помечен {@code NOT_RETRYABLE} намеренно: транзиентность здесь маскирует
         * PKI-проблемы (отсутствие fail-open политики, misconfigured trust store).
         */
        CERT_REVOCATION_UNKNOWN(
                HttpStatus.SERVICE_UNAVAILABLE,
                Retryability.NOT_RETRYABLE,
                "TLS/SSL certificate revocation status could not be determined. " +
                        "Check OCSP responder or CRL distribution point availability."),

        CERT_INVALID(
                HttpStatus.SERVICE_UNAVAILABLE,
                Retryability.NOT_RETRYABLE,
                "The TLS/SSL certificate is malformed or cannot be parsed."),

        /**
         * Local keystore/key configuration error.
         * {@code 500 INTERNAL_SERVER_ERROR} — это ошибка конфигурации нашего сервиса, а не upstream.
         */
        LOCAL_KEY_ERROR(
                HttpStatus.INTERNAL_SERVER_ERROR,
                Retryability.NOT_RETRYABLE,
                "The local TLS key or keystore configuration is invalid. " +
                        "Verify the service keystore and certificate configuration."),

        /** Netty: первый прочитанный байт не похож на TLS record (peer прислал plaintext / wrong port). */
        NETTY_NOT_SSL_RECORD(
                HttpStatus.SERVICE_UNAVAILABLE,
                Retryability.NOT_RETRYABLE,
                "Received a non-TLS record on a TLS connection. " +
                        "The remote endpoint did not initiate a TLS handshake."),

        /** Netty: handshake не уложился в configured timeout. */
        NETTY_HANDSHAKE_TIMEOUT(
                HttpStatus.GATEWAY_TIMEOUT,
                Retryability.NOT_RETRYABLE,
                "TLS/SSL handshake timed out before completion. " +
                        "Check upstream availability and handshake timeout configuration."),

        /** Netty: операция SSL/TLS на уже закрытом engine. */
        NETTY_CLOSED_ENGINE(
                HttpStatus.SERVICE_UNAVAILABLE,
                Retryability.NOT_RETRYABLE,
                "TLS/SSL engine is already closed. " +
                        "The secure connection was terminated before the operation completed."),

        /**
         * Generic SSL — fallback. Retryability=UNSPECIFIED:
         * без классификации причины невозможно сказать, безопасен ли retry.
         */
        GENERIC_SSL(
                HttpStatus.SERVICE_UNAVAILABLE,
                Retryability.UNSPECIFIED,
                "A TLS/SSL error occurred while establishing a secure connection to an upstream service.");

        private final HttpStatus httpStatus;
        private final Retryability retryability;
        private final String message;

        SslErrorType(@NonNull HttpStatus httpStatus,
                     @NonNull Retryability retryability,
                     @NonNull String message) {
            this.httpStatus = httpStatus;
            this.retryability = retryability;
            this.message = message;
        }

        @Override
        @NonNull
        public HttpStatus httpStatus() {
            return httpStatus;
        }

        @Override
        @NonNull
        public Retryability retryability() {
            return retryability;
        }

        @Override
        @NonNull
        public String message() {
            return message;
        }
    }
}
