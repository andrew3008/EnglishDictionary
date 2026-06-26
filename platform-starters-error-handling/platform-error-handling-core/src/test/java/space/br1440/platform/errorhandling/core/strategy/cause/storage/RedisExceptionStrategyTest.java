package space.br1440.platform.errorhandling.core.strategy.cause.storage;

import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.RedisCommandTimeoutException;
import io.lettuce.core.RedisConnectionException;
import io.lettuce.core.RedisException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.extension.RootCauseResolution;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;
import space.br1440.platform.errorhandling.model.error_detail.RetryInfoDetail;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RedisExceptionStrategy")
class RedisExceptionStrategyTest {

    private RedisExceptionStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new RedisExceptionStrategy(new StackHintExtractor(false, List.of()));
    }

    @Nested
    @DisplayName("supportsCause")
    class SupportsCause {

        @Test
        void acceptsLettuceRedisException() {
            // when / then
            assertThat(strategy.supportsCause(new RedisException("generic"))).isTrue();
        }

        @Test
        void acceptsLettuceConnectionException() {
            // when / then
            assertThat(strategy.supportsCause(new RedisConnectionException("conn failed"))).isTrue();
        }

        @Test
        void acceptsLettuceTimeoutException() {
            // when / then
            assertThat(strategy.supportsCause(
                    new RedisCommandTimeoutException("Command timed out after 5000 millisecond(s)"))).isTrue();
        }

        @Test
        void rejectsNonRedisExceptions() {
            // when / then
            assertThat(strategy.supportsCause(new RuntimeException("not redis"))).isFalse();
            assertThat(strategy.supportsCause(new IllegalStateException("nope"))).isFalse();
        }
    }

    @Nested
    @DisplayName("classification — Lettuce driver")
    class LettuceClassification {

        @ParameterizedTest(name = "{0} -> {1} / {2}")
        @MethodSource("lettuceCases")
        void classifiesLettuceExceptionsCorrectly(String label,
                                                   String expectedReason,
                                                   HttpStatus expectedStatus,
                                                   boolean expectedRetryable,
                                                   Throwable cause) {
            // when
            RootCauseResolution resolution = strategy.extractCauseDetails(cause);

            // then
            assertThat(extractReason(resolution)).isEqualTo(expectedReason);
            assertThat(resolution.httpStatus()).isEqualTo(expectedStatus);

            boolean hasRetryInfo = resolution.details().stream()
                    .anyMatch(d -> d instanceof RetryInfoDetail);
            assertThat(hasRetryInfo).as("retryable=" + expectedRetryable).isEqualTo(expectedRetryable);
        }

        static Stream<Arguments> lettuceCases() {
            return Stream.of(
                    Arguments.of("RedisConnectionException", "CONNECTION",
                            HttpStatus.SERVICE_UNAVAILABLE, true,
                            new RedisConnectionException("Unable to connect")),

                    Arguments.of("RedisCommandTimeoutException", "TIMEOUT",
                            HttpStatus.GATEWAY_TIMEOUT, true,
                            new RedisCommandTimeoutException("Command timed out after 5000 millisecond(s)")),

                    Arguments.of("RedisCommandExecutionException", "DATA",
                            HttpStatus.BAD_GATEWAY, false,
                            new RedisCommandExecutionException("WRONGTYPE Operation")),

                    Arguments.of("RedisException (queue overflow)", "OVERLOADED",
                            HttpStatus.SERVICE_UNAVAILABLE, true,
                            new RedisException("Request queue size exceeded: 64")),

                    Arguments.of("RedisException (generic)", "GENERIC",
                            HttpStatus.SERVICE_UNAVAILABLE, false,
                            new RedisException("unknown redis error"))
            );
        }
    }

    @Nested
    @DisplayName("diagnosticMetadata structure")
    class MetadataStructure {

        @Test
        void alwaysContainsExceptionClassAndCacheProvider() {
            // given
            RedisException ex = new RedisException("test error");

            // when
            Map<String, String> metadata = extractDiagnosticMetadata(
                    strategy.extractCauseDetails(ex));

            // then
            assertThat(metadata).containsKey("exceptionClass");
            assertThat(metadata.get("exceptionClass"))
                    .isEqualTo(RedisException.class.getName());
            assertThat(metadata).containsEntry("cacheProvider", "REDIS");
        }

        @Test
        void lettuce_hasDriverField() {
            // given
            RedisConnectionException ex = new RedisConnectionException("conn failed");

            // when
            Map<String, String> metadata = extractDiagnosticMetadata(
                    strategy.extractCauseDetails(ex));

            // then
            assertThat(metadata).containsEntry("redisDriver", "LETTUCE");
        }

        @Test
        void timeout_hasTimeoutMs() {
            // given
            RedisCommandTimeoutException ex =
                    new RedisCommandTimeoutException("Command timed out after 6000 millisecond(s)");

            // when
            Map<String, String> metadata = extractDiagnosticMetadata(
                    strategy.extractCauseDetails(ex));

            // then
            assertThat(metadata).containsKey("timeoutMs");
            assertThat(metadata.get("timeoutMs")).isEqualTo("6000");
        }

        @Test
        void overloaded_hasOverloadedReason() {
            // given
            RedisException ex = new RedisException("Request queue size exceeded: 128");

            // when
            Map<String, String> metadata = extractDiagnosticMetadata(
                    strategy.extractCauseDetails(ex));

            // then
            assertThat(metadata).containsKey("overloadedReason");
            assertThat(metadata.get("overloadedReason")).isEqualTo("QUEUE_FULL");
        }

        @Test
        void domainIsRedis() {
            // when
            RootCauseResolution resolution = strategy.extractCauseDetails(
                    new RedisException("error"));

            // then
            ErrorInfoDetail info = resolution.details().stream()
                    .filter(d -> d instanceof ErrorInfoDetail)
                    .map(d -> (ErrorInfoDetail) d)
                    .findFirst()
                    .orElseThrow();
            assertThat(info.getDomain()).isEqualTo("redis");
        }
    }

    @Nested
    @DisplayName("CWE-209 — no credentials in metadata")
    class SecurityCompliance {

        @Test
        void causeMessage_truncated() {
            // given
            String longMsg = "ERR " + "x".repeat(500);
            RedisException ex = new RedisException(longMsg);

            // when
            Map<String, String> metadata = extractDiagnosticMetadata(
                    strategy.extractCauseDetails(ex));

            // then
            if (metadata.containsKey("causeMessage")) {
                assertThat(metadata.get("causeMessage").length()).isLessThan(500);
            }
        }

        @Test
        void publicMessage_neverContainsConnectionDetails() {
            // given
            RedisConnectionException ex =
                    new RedisConnectionException("Unable to connect to redis://secret-host:6379");

            // when
            RootCauseResolution resolution = strategy.extractCauseDetails(ex);

            // then
            assertThat(resolution.message()).doesNotContain("secret-host");
            assertThat(resolution.message()).doesNotContain("6379");
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
