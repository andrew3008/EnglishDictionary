package space.br1440.platform.errorhandling.core.strategy.cause.enterprise;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.vault.VaultException;
import org.springframework.vault.authentication.VaultLoginException;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.extension.LogPolicy;
import space.br1440.platform.errorhandling.extension.RootCauseResolution;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;
import space.br1440.platform.errorhandling.model.error_detail.RetryInfoDetail;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("VaultExceptionStrategy")
class VaultExceptionStrategyTest {

    private VaultExceptionStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new VaultExceptionStrategy(new StackHintExtractor(false, List.of()));
    }

    @Nested
    @DisplayName("supportsCause")
    class SupportsCause {

        @Test
        void acceptsVaultException() {
            // when / then
            assertThat(strategy.supportsCause(new VaultException("generic"))).isTrue();
        }

        @Test
        void acceptsVaultLoginException() {
            // when / then
            assertThat(strategy.supportsCause(new VaultLoginException("login failed"))).isTrue();
        }

        @Test
        void acceptsVaultExceptionSubclass() {
            // when / then
            assertThat(strategy.supportsCause(new VaultException("sub") {})).isTrue();
        }

        @Test
        void rejectsNonVaultExceptions() {
            // when / then
            assertThat(strategy.supportsCause(new RuntimeException("not vault"))).isFalse();
            assertThat(strategy.supportsCause(new IllegalStateException("nope"))).isFalse();
        }
    }

    @Nested
    @DisplayName("classification — VaultLoginException -> AUTH_FAILURE")
    class AuthFailureClassification {

        @Test
        void loginException_classifiedAsAuthFailure() {
            // given
            VaultLoginException ex = new VaultLoginException("login failed");

            // when
            RootCauseResolution resolution = strategy.extractCauseDetails(ex);

            // then
            assertThat(resolution.httpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(extractReason(resolution)).isEqualTo("AUTH_FAILURE");
            assertThat(resolution.details().stream().anyMatch(d -> d instanceof RetryInfoDetail))
                    .as("AUTH_FAILURE must be retryable to be consistent with 503 semantics (RFC 9110 §15.6.4)")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("classification — cause chain fallback")
    class CauseChainClassification {

        @Test
        void connectException_classifiedAsConnectivity() {
            // given
            VaultException vault = new VaultException("connection failed",
                    new ConnectException("Connection refused"));

            // when
            RootCauseResolution resolution = strategy.extractCauseDetails(vault);

            // then
            assertThat(extractReason(resolution)).isEqualTo("CONNECTIVITY");
            assertThat(resolution.httpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(resolution.details().stream().anyMatch(d -> d instanceof RetryInfoDetail))
                    .as("CONNECTIVITY should be retryable").isTrue();
        }

        @Test
        void unknownHostException_classifiedAsConnectivity() {
            // given
            VaultException vault = new VaultException("DNS failure",
                    new UnknownHostException("vault.internal"));

            // when
            RootCauseResolution resolution = strategy.extractCauseDetails(vault);

            // then
            assertThat(extractReason(resolution)).isEqualTo("CONNECTIVITY");
        }

        @Test
        void plainVaultException_classifiedAsGeneric() {
            // given
            VaultException vault = new VaultException("something went wrong");

            // when
            RootCauseResolution resolution = strategy.extractCauseDetails(vault);

            // then
            assertThat(extractReason(resolution)).isEqualTo("GENERIC");
            assertThat(resolution.httpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        }

        @Test
        void sealedHint_classifiedAsSealed() {
            // given
            RuntimeException cause = new RuntimeException("Vault is sealed");
            VaultException vault = new VaultException("Service Unavailable", cause);

            // when
            RootCauseResolution resolution = strategy.extractCauseDetails(vault);

            // then
            assertThat(extractReason(resolution)).isEqualTo("GENERIC");
        }
    }

    @Nested
    @DisplayName("CWE-209 / CWE-312 — sensitive data exclusion")
    class SecurityCompliance {

        @Test
        void causeMessage_neverInMetadata() {
            // given
            String sensitiveMessage = "Status 403 Forbidden: secret/data/payments " +
                    "with token s.1234567890abcdef policy=admin";
            VaultException vault = new VaultException(sensitiveMessage);

            // when
            RootCauseResolution resolution = strategy.extractCauseDetails(vault);
            Map<String, String> metadata = extractDiagnosticMetadata(resolution);

            // then
            assertThat(metadata).doesNotContainKey("causeMessage");
            assertThat(metadata).doesNotContainKey("rootCauseMessage");
            assertThat(metadata.values().stream().noneMatch(v -> v.contains("s.1234567890")))
                    .as("token must not leak into any metadata value").isTrue();
            assertThat(metadata.values().stream().noneMatch(v -> v.contains("secret/data/payments")))
                    .as("secret path must not leak").isTrue();
            assertThat(metadata.values().stream().noneMatch(v -> v.contains("policy=admin")))
                    .as("policy names must not leak").isTrue();
        }

        @Test
        void nestedNetworkCause_exposesOnlyClassAndType_noMessageLeak() {
            // given
            // ConnectException message typically contains host:port of the upstream Vault cluster.
            // Even with sanitization/truncation, exposing "vault.internal:8200" is a CWE-209 leak:
            // it can be used for lateral movement. We guard against this by never publishing
            // nestedCauseMessage — only the class name and normalized category.
            ConnectException networkCause = new ConnectException("Connection refused: vault.internal:8200");
            VaultException vault = new VaultException("I/O error", networkCause);

            // when
            Map<String, String> metadata = extractDiagnosticMetadata(
                    strategy.extractCauseDetails(vault));

            // then
            assertThat(metadata).containsKey("nestedCauseClass");
            assertThat(metadata.get("nestedCauseClass"))
                    .isEqualTo(ConnectException.class.getName());
            assertThat(metadata).doesNotContainKey("nestedCauseMessage");
            assertThat(metadata.values().stream().noneMatch(v -> v.contains("vault.internal")))
                    .as("host name of upstream Vault must not leak").isTrue();
            assertThat(metadata.values().stream().noneMatch(v -> v.contains("8200")))
                    .as("port of upstream Vault must not leak").isTrue();
        }
    }

    @Nested
    @DisplayName("diagnosticMetadata structure")
    class MetadataStructure {

        @Test
        void alwaysContainsExceptionClass_vaultClassificationOnlyInReason() {
            // given
            VaultException vault = new VaultException("test");

            // when
            Map<String, String> metadata = extractDiagnosticMetadata(
                    strategy.extractCauseDetails(vault));

            // then
            assertThat(metadata).containsKey("exceptionClass");
            assertThat(metadata.get("exceptionClass"))
                    .isEqualTo(VaultException.class.getName());
            // VaultErrorType дублирует ErrorInfoDetail.reason — в diagnosticMetadata не кладём
            assertThat(metadata).doesNotContainKey("vaultErrorType");
        }

        @Test
        void connectivity_containsNestedCauseType() {
            // given
            ConnectException networkCause = new ConnectException("Connection refused: vault:8200");
            VaultException vault = new VaultException("I/O error", networkCause);

            // when
            Map<String, String> metadata = extractDiagnosticMetadata(
                    strategy.extractCauseDetails(vault));

            // then
            assertThat(metadata).containsKey("nestedCauseType");
            assertThat(metadata.get("nestedCauseType")).isEqualTo("CONNECTION_REFUSED");
        }

        @Test
        void rootCauseClass_presentWhenDifferentFromTopLevel() {
            // given
            UnknownHostException deep = new UnknownHostException("vault.internal");
            RuntimeException wrapper = new RuntimeException("wrapped", deep);
            VaultException vault = new VaultException("vault error", wrapper);

            // when
            Map<String, String> metadata = extractDiagnosticMetadata(
                    strategy.extractCauseDetails(vault));

            // then
            assertThat(metadata).containsKey("rootCauseClass");
        }

        @Test
        void domainIsVault() {
            // when
            RootCauseResolution resolution = strategy.extractCauseDetails(
                    new VaultException("error"));

            // then
            ErrorInfoDetail info = resolution.details().stream()
                    .filter(d -> d instanceof ErrorInfoDetail)
                    .map(d -> (ErrorInfoDetail) d)
                    .findFirst()
                    .orElseThrow();
            assertThat(info.getDomain()).isEqualTo("vault");
        }
    }

    @Nested
    @DisplayName("logPolicy")
    class LogPolicyTests {

        @Test
        void alwaysReturnsError() {
            // given
            ErrorEntry dummyEntry = ErrorEntry.builder()
                    .httpStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                    .errorCode("INTERNAL")
                    .message("error")
                    .build();

            // when / then
            Exception ex1 = new RuntimeException("wrapper", new VaultException("x"));
            assertThat(strategy.logPolicy(ex1, dummyEntry)).isEqualTo(LogPolicy.ERROR);

            Exception ex2 = new RuntimeException("wrapper", new VaultLoginException("y"));
            assertThat(strategy.logPolicy(ex2, dummyEntry)).isEqualTo(LogPolicy.ERROR);
        }
    }

    private static String extractReason(RootCauseResolution resolution) {
        return resolution.details().stream()
                .filter(d -> d instanceof ErrorInfoDetail)
                .map(d -> (ErrorInfoDetail) d)
                .map(ErrorInfoDetail::getReason)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No ErrorInfoDetail found"));
    }

    private static Map<String, String> extractDiagnosticMetadata(RootCauseResolution resolution) {
        return resolution.details().stream()
                .filter(d -> d instanceof ErrorInfoDetail)
                .map(d -> (ErrorInfoDetail) d)
                .map(ErrorInfoDetail::getDiagnosticMetadata)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No diagnosticMetadata found"));
    }
}
