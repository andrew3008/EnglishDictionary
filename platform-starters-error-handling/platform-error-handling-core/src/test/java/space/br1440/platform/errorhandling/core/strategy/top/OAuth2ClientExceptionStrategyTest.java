package space.br1440.platform.errorhandling.core.strategy.top;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.client.ClientAuthorizationException;
import org.springframework.security.oauth2.client.ClientAuthorizationRequiredException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import space.br1440.platform.errorhandling.core.resolver.CauseChainResolver;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.extension.ErrorHandlingContext;
import space.br1440.platform.errorhandling.extension.LogPolicy;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;
import space.br1440.platform.errorhandling.model.error_detail.RetryInfoDetail;

import java.net.ConnectException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OAuth2ClientExceptionStrategy")
class OAuth2ClientExceptionStrategyTest {

    private OAuth2ClientExceptionStrategy strategy;

    private static ErrorHandlingContext context(String path) {
        return ErrorHandlingContext.of(
                new ErrorHandlingProperties(), path, new StackHintExtractor(false, List.of()));
    }

    @BeforeEach
    void setUp() {
        CauseChainResolver resolver = new CauseChainResolver(Collections.emptyList());
        strategy = new OAuth2ClientExceptionStrategy(resolver);
    }

    @Nested
    @DisplayName("supports")
    class Supports {

        @Test
        void acceptsOAuth2AuthenticationException() {
            // when / then
            assertThat(strategy.supports(
                    new OAuth2AuthenticationException(new OAuth2Error("invalid_token"), "expired")))
                    .isTrue();
        }

        @Test
        void acceptsClientAuthorizationRequiredException() {
            // when / then
            assertThat(strategy.supports(
                    new ClientAuthorizationRequiredException("my-client")))
                    .isTrue();
        }

        @Test
        void acceptsClientAuthorizationException() {
            // when / then
            assertThat(strategy.supports(
                    new ClientAuthorizationException(new OAuth2Error("invalid_client"), "my-client")))
                    .isTrue();
        }

        @Test
        void rejectsUnrelatedExceptions() {
            // when / then
            assertThat(strategy.supports(new RuntimeException("nope"))).isFalse();
            assertThat(strategy.supports(new IllegalArgumentException("bad"))).isFalse();
        }
    }

    @Nested
    @DisplayName("classification — OAuth2AuthenticationException by RFC 6749 error code")
    class Rfc6749Classification {

        @ParameterizedTest(name = "errorCode={0} -> reason={1}, status={2}")
        @MethodSource("rfc6749Cases")
        void classifiesByErrorCode(String errorCode, String expectedReason, HttpStatus expectedStatus) {
            // given
            OAuth2Error error = new OAuth2Error(errorCode, "desc", null);
            OAuth2AuthenticationException ex = new OAuth2AuthenticationException(error, "test");

            // when
            ErrorEntry entry = strategy.handle(ex, context("/api/resource"));

            // then
            assertThat(entry.getHttpStatus()).isEqualTo(expectedStatus);
            assertThat(extractReason(entry)).isEqualTo(expectedReason);
        }

        static Stream<Arguments> rfc6749Cases() {
            return Stream.of(
                    Arguments.of("invalid_token", "INVALID_TOKEN", HttpStatus.UNAUTHORIZED),
                    Arguments.of("expired_token", "TOKEN_EXPIRED", HttpStatus.UNAUTHORIZED),
                    Arguments.of("token_expired", "TOKEN_EXPIRED", HttpStatus.UNAUTHORIZED),
                    Arguments.of("insufficient_scope", "INSUFFICIENT_SCOPE", HttpStatus.FORBIDDEN),
                    Arguments.of("access_denied", "ACCESS_DENIED", HttpStatus.FORBIDDEN),
                    Arguments.of("invalid_grant", "INVALID_GRANT", HttpStatus.BAD_REQUEST),
                    Arguments.of("invalid_client", "CLIENT_AUTHORIZATION_FAILED", HttpStatus.SERVICE_UNAVAILABLE),
                    Arguments.of("server_error", "AUTHORIZATION_SERVER_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE),
                    Arguments.of("temporarily_unavailable", "AUTHORIZATION_SERVER_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE),
                    Arguments.of("unknown_custom_code", "OAUTH2_ERROR", HttpStatus.UNAUTHORIZED)
            );
        }
    }

    @Nested
    @DisplayName("classification — ClientAuthorization exceptions")
    class ClientAuthorizationClassification {

        @Test
        void clientAuthorizationRequired_classifiedAsAuthorizationRequired() {
            // given
            ClientAuthorizationRequiredException ex =
                    new ClientAuthorizationRequiredException("my-client");

            // when
            ErrorEntry entry = strategy.handle(ex, context("/"));

            // then
            assertThat(entry.getHttpStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
            assertThat(extractReason(entry)).isEqualTo("AUTHORIZATION_REQUIRED");
        }

        @Test
        void clientAuthorizationFailed_classifiedAsClientFailed() {
            // given
            ClientAuthorizationException ex = new ClientAuthorizationException(
                    new OAuth2Error("invalid_client"), "my-client");

            // when
            ErrorEntry entry = strategy.handle(ex, context("/"));

            // then
            assertThat(entry.getHttpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(extractReason(entry)).isEqualTo("CLIENT_AUTHORIZATION_FAILED");
        }

        @Test
        void clientAuthorizationWithNetworkCause_classifiedAsServerUnavailable() {
            // given
            ClientAuthorizationException ex = new ClientAuthorizationException(
                    new OAuth2Error("server_error"), "my-client",
                    new ConnectException("Connection refused"));

            // when
            ErrorEntry entry = strategy.handle(ex, context("/"));

            // then
            assertThat(entry.getHttpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(extractReason(entry)).isEqualTo("AUTHORIZATION_SERVER_UNAVAILABLE");
        }
    }

    @Nested
    @DisplayName("CWE-209 / CWE-312 — token redaction")
    class SecurityCompliance {

        @Test
        void bearerToken_redacted() {
            // given
            OAuth2Error error = new OAuth2Error("invalid_token",
                    "Bearer eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ1c2VyIn0.signature is invalid", null);
            OAuth2AuthenticationException ex = new OAuth2AuthenticationException(error,
                    "Bearer eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ1c2VyIn0.signature is invalid");

            // when
            ErrorEntry entry = strategy.handle(ex, context("/"));
            Map<String, String> metadata = extractDiagnosticMetadata(entry);
            String causeMsg = metadata.get("causeMessage");

            // then
            assertThat(causeMsg).doesNotContain("eyJhbGciOiJSUzI1NiJ9");
            assertThat(causeMsg.contains("[REDACTED]") || causeMsg.contains("[JWT_REDACTED]"))
                    .as("causeMessage should contain redaction marker").isTrue();
        }

        @Test
        void accessTokenParam_redacted() {
            // given
            OAuth2AuthenticationException ex = new OAuth2AuthenticationException(
                    new OAuth2Error("invalid_token"),
                    "access_token=abc123secret&scope=read");

            // when
            ErrorEntry entry = strategy.handle(ex, context("/"));
            Map<String, String> metadata = extractDiagnosticMetadata(entry);
            String causeMsg = metadata.get("causeMessage");

            // then
            assertThat(causeMsg).doesNotContain("abc123secret");
        }

        @Test
        void jwtCompact_redacted() {
            // given
            String jwt = "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0In0.sig";
            OAuth2AuthenticationException ex = new OAuth2AuthenticationException(
                    new OAuth2Error("invalid_token"), "Token " + jwt + " is expired");

            // when
            ErrorEntry entry = strategy.handle(ex, context("/"));
            Map<String, String> metadata = extractDiagnosticMetadata(entry);
            String causeMsg = metadata.get("causeMessage");

            // then
            assertThat(causeMsg).doesNotContain("eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9");
            assertThat(causeMsg).contains("[JWT_REDACTED]");
        }

        @Test
        void publicMessage_neverContainsToken() {
            // given
            String jwt = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ1c2VyIn0.signature";
            OAuth2AuthenticationException ex = new OAuth2AuthenticationException(
                    new OAuth2Error("invalid_token"), jwt);

            // when
            ErrorEntry entry = strategy.handle(ex, context("/"));

            // then
            assertThat(entry.getMessage()).doesNotContain("eyJ");
        }
    }

    @Nested
    @DisplayName("diagnosticMetadata structure")
    class MetadataStructure {

        @Test
        void containsExceptionClassAndErrorType() {
            // given
            OAuth2AuthenticationException ex = new OAuth2AuthenticationException(
                    new OAuth2Error("invalid_token"), "bad token");

            // when
            Map<String, String> metadata = extractDiagnosticMetadata(
                    strategy.handle(ex, context("/")));

            // then
            assertThat(metadata).containsKey("exceptionClass");
            assertThat(metadata).containsKey("oauth2ErrorType");
            assertThat(metadata.get("oauth2ErrorType")).isEqualTo("INVALID_TOKEN");
        }

        @Test
        void containsOAuth2ErrorCode() {
            // given
            OAuth2AuthenticationException ex = new OAuth2AuthenticationException(
                    new OAuth2Error("insufficient_scope"), "need more scope");

            // when
            Map<String, String> metadata = extractDiagnosticMetadata(
                    strategy.handle(ex, context("/")));

            // then
            assertThat(metadata).containsEntry("oauth2ErrorCode", "insufficient_scope");
        }

        @Test
        void containsClientRegistrationId_whenAvailable() {
            // given
            ClientAuthorizationException ex = new ClientAuthorizationException(
                    new OAuth2Error("invalid_client"), "payment-service");

            // when
            Map<String, String> metadata = extractDiagnosticMetadata(
                    strategy.handle(ex, context("/")));

            // then
            assertThat(metadata).containsEntry("clientRegistrationId", "payment-service");
        }

        @Test
        void domainIsOauth2() {
            // given
            OAuth2AuthenticationException ex = new OAuth2AuthenticationException(
                    new OAuth2Error("invalid_token"), "test");

            // when
            ErrorEntry entry = strategy.handle(ex, context("/"));

            // then
            ErrorInfoDetail info = entry.getDetails().stream()
                    .filter(d -> d instanceof ErrorInfoDetail)
                    .map(d -> (ErrorInfoDetail) d)
                    .findFirst()
                    .orElseThrow();
            assertThat(info.getDomain()).isEqualTo("oauth2");
        }
    }

    @Nested
    @DisplayName("logPolicy")
    class LogPolicyTests {

        @Test
        void clientError_returnsWarn() {
            // given
            OAuth2AuthenticationException ex = new OAuth2AuthenticationException(
                    new OAuth2Error("invalid_token"), "expired");
            ErrorEntry entry = strategy.handle(ex, context("/"));

            // when / then
            assertThat(strategy.logPolicy(ex, entry)).isEqualTo(LogPolicy.WARN);
        }

        @Test
        void serverError_returnsError() {
            // given
            ClientAuthorizationException ex = new ClientAuthorizationException(
                    new OAuth2Error("server_error"), "my-client",
                    new ConnectException("Connection refused"));
            ErrorEntry entry = strategy.handle(ex, context("/"));

            // when / then
            assertThat(strategy.logPolicy(ex, entry)).isEqualTo(LogPolicy.ERROR);
        }
    }

    @Nested
    @DisplayName("retryability")
    class RetryabilityTests {

        @Test
        void authServerUnavailable_isRetryable() {
            // given
            OAuth2AuthenticationException ex = new OAuth2AuthenticationException(
                    new OAuth2Error("temporarily_unavailable"), "retry later");

            // when
            ErrorEntry entry = strategy.handle(ex, context("/"));

            // then
            boolean hasRetryInfo = entry.getDetails().stream()
                    .anyMatch(d -> d instanceof RetryInfoDetail);
            assertThat(hasRetryInfo).isTrue();
        }

        @Test
        void invalidToken_isNotRetryable() {
            // given
            OAuth2AuthenticationException ex = new OAuth2AuthenticationException(
                    new OAuth2Error("invalid_token"), "bad token");

            // when
            ErrorEntry entry = strategy.handle(ex, context("/"));

            // then
            boolean hasRetryInfo = entry.getDetails().stream()
                    .anyMatch(d -> d instanceof RetryInfoDetail);
            assertThat(hasRetryInfo).isFalse();
        }
    }

    private static String extractReason(ErrorEntry entry) {
        return entry.getDetails().stream()
                .filter(d -> d instanceof ErrorInfoDetail)
                .map(d -> (ErrorInfoDetail) d)
                .map(ErrorInfoDetail::getReason)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No ErrorInfoDetail found"));
    }

    private static Map<String, String> extractDiagnosticMetadata(ErrorEntry entry) {
        return entry.getDetails().stream()
                .filter(d -> d instanceof ErrorInfoDetail)
                .map(d -> (ErrorInfoDetail) d)
                .map(ErrorInfoDetail::getDiagnosticMetadata)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No diagnosticMetadata found"));
    }
}
