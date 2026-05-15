package space.br1440.platform.errorhandling.core.strategy.cause.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.oauth2.client.ClientAuthorizationException;
import org.springframework.security.oauth2.client.ClientAuthorizationRequiredException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.core.util.sanitize.OAuth2DiagnosticSanitizer;
import space.br1440.platform.errorhandling.extension.LogPolicy;
import space.br1440.platform.errorhandling.extension.Retryability;
import space.br1440.platform.errorhandling.extension.RootCauseResolution;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;
import space.br1440.platform.errorhandling.model.error_detail.RetryInfoDetail;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import javax.net.ssl.SSLHandshakeException;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OAuth2AuthorizationExceptionStrategy")
class OAuth2AuthorizationExceptionStrategyTest {

    private OAuth2AuthorizationExceptionStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new OAuth2AuthorizationExceptionStrategy(new StackHintExtractor(false, List.of()));
    }

    @Nested
    @DisplayName("supportsCause")
    class SupportsCause {

        @Test
        void acceptsOAuth2AuthenticationException() {
            assertThat(strategy.supportsCause(
                    new OAuth2AuthenticationException(new OAuth2Error("invalid_token"), "x"))).isTrue();
        }

        @Test
        void acceptsClientAuthorizationException() {
            assertThat(strategy.supportsCause(
                    new ClientAuthorizationException(new OAuth2Error("invalid_client"), "c"))).isTrue();
        }

        @Test
        void acceptsClientAuthorizationRequiredException() {
            assertThat(strategy.supportsCause(
                    new ClientAuthorizationRequiredException("c"))).isTrue();
        }

        @Test
        void acceptsBadCredentialsException() {
            assertThat(strategy.supportsCause(new BadCredentialsException("bad creds"))).isTrue();
        }

        @Test
        void acceptsInsufficientAuthenticationException() {
            assertThat(strategy.supportsCause(new InsufficientAuthenticationException("nope"))).isTrue();
        }

        @Test
        void rejectsUnrelatedException() {
            assertThat(strategy.supportsCause(new RuntimeException("plain"))).isFalse();
            assertThat(strategy.supportsCause(new IllegalArgumentException("nope"))).isFalse();
        }
    }

    @Nested
    @DisplayName("classification — RFC 6749/6750/9449 error codes")
    class Rfc6749Classification {

        @ParameterizedTest(name = "errorCode={0} -> reason={1}, status={2}, retryable={3}")
        @MethodSource("rfcCases")
        void classifiesByErrorCode(String errorCode,
                                   String expectedReason,
                                   HttpStatus expectedStatus,
                                   boolean expectedRetryable) {
            OAuth2AuthenticationException ex =
                    new OAuth2AuthenticationException(new OAuth2Error(errorCode, "desc", null), "test");

            RootCauseResolution res = strategy.extractCauseDetails(ex);

            assertThat(res.httpStatus()).isEqualTo(expectedStatus);
            assertThat(extractReason(res)).isEqualTo(expectedReason);
            assertThat(hasRetryInfo(res)).isEqualTo(expectedRetryable);
        }

        static Stream<Arguments> rfcCases() {
            return Stream.of(
                    Arguments.of("invalid_token",            "INVALID_TOKEN",                    HttpStatus.UNAUTHORIZED,        false),
                    Arguments.of("expired_token",            "TOKEN_EXPIRED",                    HttpStatus.UNAUTHORIZED,        true),
                    Arguments.of("token_expired",            "TOKEN_EXPIRED",                    HttpStatus.UNAUTHORIZED,        true),
                    Arguments.of("insufficient_scope",       "INSUFFICIENT_SCOPE",               HttpStatus.FORBIDDEN,           false),
                    Arguments.of("access_denied",            "ACCESS_DENIED",                    HttpStatus.FORBIDDEN,           false),
                    Arguments.of("invalid_grant",            "INVALID_GRANT",                    HttpStatus.BAD_REQUEST,         false),
                    Arguments.of("invalid_request",          "INVALID_REQUEST",                  HttpStatus.BAD_REQUEST,         false),
                    Arguments.of("invalid_scope",            "INVALID_SCOPE",                    HttpStatus.BAD_REQUEST,         false),
                    Arguments.of("unauthorized_client",      "UNAUTHORIZED_CLIENT",              HttpStatus.FORBIDDEN,           false),
                    Arguments.of("unsupported_grant_type",   "UNSUPPORTED_GRANT_TYPE",           HttpStatus.BAD_REQUEST,         false),
                    Arguments.of("unsupported_response_type","UNSUPPORTED_GRANT_TYPE",           HttpStatus.BAD_REQUEST,         false),
                    Arguments.of("invalid_client",           "CLIENT_AUTHORIZATION_FAILED",      HttpStatus.SERVICE_UNAVAILABLE, false),
                    Arguments.of("server_error",             "AUTHORIZATION_SERVER_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE, true),
                    Arguments.of("temporarily_unavailable",  "AUTHORIZATION_SERVER_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE, true),
                    Arguments.of("invalid_dpop_proof",       "INVALID_DPOP_PROOF",               HttpStatus.UNAUTHORIZED,        false),
                    Arguments.of("use_dpop_nonce",           "USE_DPOP_NONCE",                   HttpStatus.UNAUTHORIZED,        true),
                    Arguments.of("mtls_required",            "MTLS_REQUIRED",                    HttpStatus.UNAUTHORIZED,        false),
                    Arguments.of("unknown_vendor_code",      "GENERIC",                          HttpStatus.UNAUTHORIZED,        false)
            );
        }
    }

    @Nested
    @DisplayName("classification — non-OAuth2 Spring Security exceptions")
    class SpringSecurityClassification {

        @Test
        void badCredentialsException() {
            RootCauseResolution res = strategy.extractCauseDetails(new BadCredentialsException("bad"));

            assertThat(extractReason(res)).isEqualTo("BAD_CREDENTIALS");
            assertThat(res.httpStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(extractDomain(res))
                    .as("non-OAuth2 Spring Security исключения должны иметь domain=security")
                    .isEqualTo("security");
        }

        @Test
        void insufficientAuthenticationException() {
            RootCauseResolution res = strategy.extractCauseDetails(new InsufficientAuthenticationException("nope"));

            assertThat(extractReason(res)).isEqualTo("INSUFFICIENT_AUTHENTICATION");
            assertThat(res.httpStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(extractDomain(res)).isEqualTo("security");
        }

        @Test
        void oauth2AuthenticationException_hasOauth2Domain() {
            RootCauseResolution res = strategy.extractCauseDetails(
                    new OAuth2AuthenticationException(new OAuth2Error("invalid_token"), "x"));

            assertThat(extractDomain(res)).isEqualTo("oauth2");
        }
    }

    @Nested
    @DisplayName("classification — ClientAuthorizationException")
    class ClientAuthorizationClassification {

        @Test
        void clientAuthorizationFailed_classifiedAsClientFailed() {
            ClientAuthorizationException ex =
                    new ClientAuthorizationException(new OAuth2Error("invalid_client"), "my-client");

            RootCauseResolution res = strategy.extractCauseDetails(ex);

            assertThat(extractReason(res)).isEqualTo("CLIENT_AUTHORIZATION_FAILED");
            assertThat(res.httpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(res.retryability()).isEqualTo(Retryability.NOT_RETRYABLE);
        }

        @Test
        void clientAuthorizationRequired_classifiedAsRequired() {
            ClientAuthorizationRequiredException ex = new ClientAuthorizationRequiredException("c");

            RootCauseResolution res = strategy.extractCauseDetails(ex);

            assertThat(extractReason(res)).isEqualTo("AUTHORIZATION_REQUIRED");
            assertThat(res.httpStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
        }
    }

    @Nested
    @DisplayName("CWE-209 / CWE-312 — token redaction in causeMessage")
    class TokenRedaction {

        @Test
        void bearerToken_redacted_caseInsensitive() {
            OAuth2AuthenticationException ex = new OAuth2AuthenticationException(
                    new OAuth2Error("invalid_token"),
                    "bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1IjoiZSJ9.SIGNATURE rejected");

            Map<String, String> meta = extractMetadata(strategy.extractCauseDetails(ex));

            assertThat(meta.get("causeMessage"))
                    .doesNotContain("eyJhbGciOiJIUzI1NiJ9")
                    .doesNotContain("SIGNATURE")
                    .containsAnyOf(OAuth2DiagnosticSanitizer.REDACTED, OAuth2DiagnosticSanitizer.JWT_REDACTED);
        }

        @Test
        void accessToken_redacted_inQueryString() {
            OAuth2AuthenticationException ex = new OAuth2AuthenticationException(
                    new OAuth2Error("invalid_token"),
                    "rejected: access_token=abc123secret&scope=read");

            Map<String, String> meta = extractMetadata(strategy.extractCauseDetails(ex));

            assertThat(meta.get("causeMessage"))
                    .doesNotContain("abc123secret")
                    .contains(OAuth2DiagnosticSanitizer.REDACTED);
        }

        @Test
        void refreshTokenAndIdToken_redacted() {
            OAuth2AuthenticationException ex = new OAuth2AuthenticationException(
                    new OAuth2Error("invalid_grant"),
                    "refresh_token=rt_secret_xyz id_token:idsecret999");

            Map<String, String> meta = extractMetadata(strategy.extractCauseDetails(ex));

            assertThat(meta.get("causeMessage"))
                    .doesNotContain("rt_secret_xyz")
                    .doesNotContain("idsecret999");
        }

        @Test
        void clientSecret_redacted() {
            OAuth2AuthenticationException ex = new OAuth2AuthenticationException(
                    new OAuth2Error("invalid_client"),
                    "client_secret=topsecret123 leaked into log");

            Map<String, String> meta = extractMetadata(strategy.extractCauseDetails(ex));

            assertThat(meta.get("causeMessage")).doesNotContain("topsecret123");
        }

        @Test
        void compactJwt_redacted_evenWithoutBearerPrefix() {
            String jwt = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ1c2VyIn0.signature";
            OAuth2AuthenticationException ex = new OAuth2AuthenticationException(
                    new OAuth2Error("invalid_token"), "Token " + jwt + " is bad");

            Map<String, String> meta = extractMetadata(strategy.extractCauseDetails(ex));

            assertThat(meta.get("causeMessage"))
                    .doesNotContain("eyJhbGciOiJSUzI1NiJ9")
                    .contains(OAuth2DiagnosticSanitizer.JWT_REDACTED);
        }

        @Test
        void noFalsePositive_onWordsLikeExpression() {
            OAuth2AuthenticationException ex = new OAuth2AuthenticationException(
                    new OAuth2Error("invalid_request"),
                    "Invalid expression in claim filter; experience missing");

            Map<String, String> meta = extractMetadata(strategy.extractCauseDetails(ex));

            assertThat(meta.get("causeMessage"))
                    .as("слова 'expression'/'experience' не должны redactиться как JWT")
                    .contains("expression")
                    .contains("experience");
        }
    }

    @Nested
    @DisplayName("CWE-117 — log injection scrub")
    class LogInjection {

        @Test
        void crLfStripped_fromCauseMessage() {
            OAuth2AuthenticationException ex = new OAuth2AuthenticationException(
                    new OAuth2Error("invalid_token"),
                    "first line\r\nINJECTED ERROR fake-line\nstill same");

            Map<String, String> meta = extractMetadata(strategy.extractCauseDetails(ex));

            assertThat(meta.get("causeMessage"))
                    .doesNotContain("\n")
                    .doesNotContain("\r");
        }

        @Test
        void trojanSourceBidiOverridesStripped() {
            String bidi = "\u202Eevil\u202C";
            OAuth2AuthenticationException ex = new OAuth2AuthenticationException(
                    new OAuth2Error("invalid_token"), "before " + bidi + " after");

            Map<String, String> meta = extractMetadata(strategy.extractCauseDetails(ex));

            assertThat(meta.get("causeMessage"))
                    .doesNotContain("\u202E")
                    .doesNotContain("\u202C");
        }
    }

    @Nested
    @DisplayName("Bearer challenge metadata (RFC 6750 §3)")
    class BearerChallenge {

        @Test
        void bearerErrorAndDescription_extracted() {
            OAuth2AuthenticationException ex = new OAuth2AuthenticationException(
                    new OAuth2Error("invalid_token", "The token is malformed", "https://example.org/help"),
                    "bad token");

            Map<String, String> meta = extractMetadata(strategy.extractCauseDetails(ex));

            assertThat(meta).containsEntry("bearerError", "invalid_token");
            assertThat(meta.get("bearerErrorDescription")).contains("malformed");
            assertThat(meta).containsEntry("oauth2ErrorCode", "invalid_token");
        }

        @Test
        void bearerErrorDescription_redacted() {
            OAuth2AuthenticationException ex = new OAuth2AuthenticationException(
                    new OAuth2Error("invalid_token",
                            "Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1In0.sig was rejected", null),
                    "bad");

            Map<String, String> meta = extractMetadata(strategy.extractCauseDetails(ex));

            assertThat(meta.get("bearerErrorDescription")).doesNotContain("eyJhbGciOiJIUzI1NiJ9");
        }
    }

    @Nested
    @DisplayName("nested cause — infrastructure failures")
    class NestedCause {

        @Test
        void connectException_classifiedAsConnectionRefused() {
            ClientAuthorizationException ex = new ClientAuthorizationException(
                    new OAuth2Error("server_error"), "my-client",
                    new ConnectException("Connection refused to idp:8443"));

            Map<String, String> meta = extractMetadata(strategy.extractCauseDetails(ex));

            assertThat(meta).containsEntry("nestedCauseClass", ConnectException.class.getName());
            assertThat(meta).containsEntry("nestedCauseType", "CONNECTION_REFUSED");
            assertThat(meta.get("nestedCauseMessage")).contains("Connection refused");
        }

        @Test
        void unknownHostException_classifiedAsDnsFailure() {
            ClientAuthorizationException ex = new ClientAuthorizationException(
                    new OAuth2Error("server_error"), "c",
                    new UnknownHostException("idp.internal"));

            Map<String, String> meta = extractMetadata(strategy.extractCauseDetails(ex));

            assertThat(meta).containsEntry("nestedCauseType", "DNS_RESOLUTION_FAILED");
        }

        @Test
        void socketTimeout_classifiedAsTimeout() {
            ClientAuthorizationException ex = new ClientAuthorizationException(
                    new OAuth2Error("server_error"), "c",
                    new SocketTimeoutException("read timeout"));

            Map<String, String> meta = extractMetadata(strategy.extractCauseDetails(ex));

            assertThat(meta).containsEntry("nestedCauseType", "SOCKET_TIMEOUT");
        }

        @Test
        void sslHandshakeException_classifiedAsTlsError() {
            ClientAuthorizationException ex = new ClientAuthorizationException(
                    new OAuth2Error("server_error"), "c",
                    new SSLHandshakeException("PKIX path validation failed"));

            Map<String, String> meta = extractMetadata(strategy.extractCauseDetails(ex));

            assertThat(meta).containsEntry("nestedCauseType", "TLS_ERROR");
        }

        @Test
        void invalidTokenError_doesNotEnrichNestedCause() {
            OAuth2AuthenticationException ex = new OAuth2AuthenticationException(
                    new OAuth2Error("invalid_token"), "bad");

            Map<String, String> meta = extractMetadata(strategy.extractCauseDetails(ex));

            assertThat(meta).doesNotContainKey("nestedCauseClass");
        }
    }

    @Nested
    @DisplayName("logPolicy")
    class LogPolicyTests {

        @Test
        void clientErrors_returnsWarn() {
            OAuth2AuthenticationException ex =
                    new OAuth2AuthenticationException(new OAuth2Error("invalid_token"), "x");
            assertThat(strategy.logPolicy(ex, null)).isEqualTo(LogPolicy.WARN);
        }

        @Test
        void infraErrors_returnsError() {
            OAuth2AuthenticationException ex =
                    new OAuth2AuthenticationException(new OAuth2Error("server_error"), "x");
            assertThat(strategy.logPolicy(ex, null)).isEqualTo(LogPolicy.ERROR);
        }

        @Test
        void badCredentials_returnsWarn() {
            assertThat(strategy.logPolicy(new BadCredentialsException("x"), null))
                    .isEqualTo(LogPolicy.WARN);
        }
    }

    @Nested
    @DisplayName("retryability — поведенческая симметрия с top-level стратегией")
    class RetryabilityContract {

        @Test
        void serverError_isRetryable() {
            OAuth2AuthenticationException ex = new OAuth2AuthenticationException(
                    new OAuth2Error("temporarily_unavailable"), "retry later");

            RootCauseResolution res = strategy.extractCauseDetails(ex);

            assertThat(res.retryability()).isEqualTo(Retryability.RETRYABLE);
            assertThat(hasRetryInfo(res)).isTrue();
        }

        @Test
        void invalidToken_isNotRetryable() {
            OAuth2AuthenticationException ex = new OAuth2AuthenticationException(
                    new OAuth2Error("invalid_token"), "x");

            RootCauseResolution res = strategy.extractCauseDetails(ex);

            assertThat(res.retryability()).isEqualTo(Retryability.NOT_RETRYABLE);
            assertThat(hasRetryInfo(res)).isFalse();
        }

        @Test
        void useDpopNonce_isRetryable() {
            OAuth2AuthenticationException ex = new OAuth2AuthenticationException(
                    new OAuth2Error("use_dpop_nonce"), "fresh nonce required");

            RootCauseResolution res = strategy.extractCauseDetails(ex);

            assertThat(res.retryability()).isEqualTo(Retryability.RETRYABLE);
        }
    }

    @Nested
    @DisplayName("ordering against top-level isInstance — BadJwt before JwtValidation before Jwt")
    class HierarchyOrdering {

        @Test
        void publicMessage_neverContainsBearerToken() {
            String jwt = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ1c2VyIn0.signature";
            OAuth2AuthenticationException ex = new OAuth2AuthenticationException(
                    new OAuth2Error("invalid_token"), "Bearer " + jwt + " bad");

            RootCauseResolution res = strategy.extractCauseDetails(ex);

            assertThat(res.message())
                    .as("публичное сообщение никогда не должно содержать токен")
                    .doesNotContain("eyJ")
                    .doesNotContain("Bearer eyJ");
        }
    }

    @Nested
    @DisplayName("diagnosticMetadata — структура")
    class MetadataStructure {

        @Test
        void containsExceptionClass() {
            RootCauseResolution res = strategy.extractCauseDetails(
                    new OAuth2AuthenticationException(new OAuth2Error("invalid_token"), "x"));

            assertThat(extractMetadata(res))
                    .containsEntry("exceptionClass", OAuth2AuthenticationException.class.getName());
        }

        @Test
        void containsClientRegistrationId_whenAvailable() {
            ClientAuthorizationException ex = new ClientAuthorizationException(
                    new OAuth2Error("invalid_client"), "payment-service");

            assertThat(extractMetadata(strategy.extractCauseDetails(ex)))
                    .containsEntry("clientRegistrationId", "payment-service");
        }
    }

    private static String extractReason(RootCauseResolution res) {
        return res.details().stream()
                .filter(d -> d instanceof ErrorInfoDetail)
                .map(d -> ((ErrorInfoDetail) d).getReason())
                .findFirst()
                .orElseThrow();
    }

    private static String extractDomain(RootCauseResolution res) {
        return res.details().stream()
                .filter(d -> d instanceof ErrorInfoDetail)
                .map(d -> ((ErrorInfoDetail) d).getDomain())
                .findFirst()
                .orElseThrow();
    }

    private static Map<String, String> extractMetadata(RootCauseResolution res) {
        return res.details().stream()
                .filter(d -> d instanceof ErrorInfoDetail)
                .map(d -> ((ErrorInfoDetail) d).getDiagnosticMetadata())
                .findFirst()
                .orElseThrow();
    }

    private static boolean hasRetryInfo(RootCauseResolution res) {
        return res.details().stream().anyMatch(d -> d instanceof RetryInfoDetail);
    }
}
