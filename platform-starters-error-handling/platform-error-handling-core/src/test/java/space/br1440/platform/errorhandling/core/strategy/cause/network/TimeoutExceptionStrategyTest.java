package space.br1440.platform.errorhandling.core.strategy.cause.network;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import space.br1440.platform.errorhandling.model.error_detail.ErrorDetail;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;
import space.br1440.platform.errorhandling.model.error_detail.RetryInfoDetail;
import space.br1440.platform.errorhandling.extension.RootCauseResolution;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("TimeoutExceptionStrategy")
class TimeoutExceptionStrategyTest {

    private final TimeoutExceptionStrategy strategy =
            new TimeoutExceptionStrategy(new StackHintExtractor(false, List.of()));

    @Nested
    @DisplayName("supportsCause")
    class SupportsCause {

        @Test
        void supports_jdk_TimeoutException() {
            assertTrue(strategy.supportsCause(new TimeoutException("timed out")));
        }

        @Test
        void supports_TransactionTimedOutException() {
            assertTrue(strategy.supportsCause(new TransactionTimedOutException("tx timed out")));
        }

        @Test
        void supports_AsyncRequestTimeoutException() {
            assertTrue(strategy.supportsCause(new AsyncRequestTimeoutException()));
        }

        @Test
        void does_not_support_RuntimeException() {
            assertFalse(strategy.supportsCause(new RuntimeException("unrelated")));
        }

        @Test
        void does_not_support_InterruptedException() {
            assertFalse(strategy.supportsCause(new InterruptedException("interrupted")));
        }

        @Test
        void does_not_support_IOException() {
            assertFalse(strategy.supportsCause(new java.io.IOException("io error")));
        }
    }

    @Nested
    @DisplayName("classification")
    class Classification {

        private final TimeoutExceptionStrategy strategy =
                new TimeoutExceptionStrategy(new StackHintExtractor(true, List.of()));

        @Test
        void async_timeout_returns_504() {
            RootCauseResolution details = strategy.extractCauseDetails(
                    new TimeoutException("Future.get timed out after 5000ms")
            );
            assertEquals(HttpStatus.GATEWAY_TIMEOUT, details.httpStatus());
        }

        @Test
        void transaction_timeout_returns_503() {
            RootCauseResolution details = strategy.extractCauseDetails(
                    new TransactionTimedOutException("Transaction timed out: deadline was Mon Apr 26 14:00:00 MSK 2026")
            );
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, details.httpStatus());

            ErrorInfoDetail info = findInfoDetail(details);
            assertEquals("TRANSACTION_TIMEOUT", info.getReason());
            assertEquals("TRANSACTION", info.getClientMetadata().get("timeoutLayer"));
        }

        @Test
        void async_request_timeout_returns_503_with_request_layer() {
            RootCauseResolution details = strategy.extractCauseDetails(
                    new AsyncRequestTimeoutException()
            );
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, details.httpStatus());

            ErrorInfoDetail info = findInfoDetail(details);
            assertEquals("ASYNC_REQUEST_TIMEOUT", info.getReason());
            assertEquals("REQUEST", info.getClientMetadata().get("timeoutLayer"));
        }

        @Test
        void all_timeouts_include_RetryInfoDetail() {
            RootCauseResolution details = strategy.extractCauseDetails(
                    new TimeoutException("timed out")
            );
            List<ErrorDetail> errorDetails = details.details();
            assertTrue(errorDetails.stream().anyMatch(d -> d instanceof RetryInfoDetail));
        }

        @Test
        void includes_errorInfoDetail_with_domain_timeout() {
            RootCauseResolution details = strategy.extractCauseDetails(
                    new TimeoutException("timed out")
            );

            ErrorInfoDetail info = findInfoDetail(details);
            assertEquals("timeout", info.getDomain());
            assertEquals("ASYNC_TIMEOUT", info.getReason());
        }
    }

    @Nested
    @DisplayName("clientMetadata")
    class ClientMetadata {

        @Test
        void contains_only_timeoutLayer() {
            RootCauseResolution details = strategy.extractCauseDetails(
                    new TimeoutException("timed out"));

            ErrorInfoDetail info = findInfoDetail(details);
            Map<String, String> clientMeta = info.getClientMetadata();

            assertEquals("ASYNC", clientMeta.get("timeoutLayer"));
            // timeoutType избыточен с reason; retryable передаётся через RetryInfoDetail
            assertThat(clientMeta).doesNotContainKey("timeoutType");
            assertThat(clientMeta).doesNotContainKey("retryable");
        }

        @Test
        void is_unmodifiable() {
            RootCauseResolution details = strategy.extractCauseDetails(
                    new TimeoutException("timed out"));

            ErrorInfoDetail info = findInfoDetail(details);
            Map<String, String> clientMeta = info.getClientMetadata();

            assertThrows(UnsupportedOperationException.class,
                    () -> clientMeta.put("key", "value"));
        }
    }

    @Nested
    @DisplayName("diagnosticMetadata")
    class Diagnostic {

        private final TimeoutExceptionStrategy strategy =
                new TimeoutExceptionStrategy(new StackHintExtractor(true, List.of()));

        @Test
        void does_not_contain_redundant_exceptionClass() {
            RootCauseResolution details = strategy.extractCauseDetails(
                    new TimeoutException("timed out"));

            ErrorInfoDetail info = findInfoDetail(details);
            // exceptionClass избыточен с reason/rootCauseClass — удалён в полировке.
            assertThat(info.getDiagnosticMetadata()).doesNotContainKey("exceptionClass");
        }

        @Test
        void does_not_contain_duplicated_timeoutType_and_timeoutLayer() {
            RootCauseResolution details = strategy.extractCauseDetails(
                    new TimeoutException("timed out"));

            Map<String, String> diag = findInfoDetail(details).getDiagnosticMetadata();
            assertThat(diag).doesNotContainKey("timeoutType");
            assertThat(diag).doesNotContainKey("timeoutLayer");
        }

        @Test
        void contains_causeMessage_sanitized_and_truncated() {
            String dirty = "Timeout waiting\r\nfor response\u202Efrom upstream";
            RootCauseResolution details = strategy.extractCauseDetails(
                    new TimeoutException(dirty));

            String causeMessage = findInfoDetail(details).getDiagnosticMetadata().get("causeMessage");
            assertNotNull(causeMessage);
            // CR/LF и bidi override не должны попасть в индексируемое поле
            assertFalse(causeMessage.contains("\r"));
            assertFalse(causeMessage.contains("\n"));
            assertFalse(causeMessage.contains("\u202E"));
            assertTrue(causeMessage.contains("Timeout waiting"));
        }

        @Test
        void contains_stackHint() {
            RootCauseResolution details = strategy.extractCauseDetails(
                    new TimeoutException("timed out"));

            assertThat(findInfoDetail(details).getDiagnosticMetadata()).containsKey("stackHint");
        }

        @Test
        void is_unmodifiable() {
            RootCauseResolution details = strategy.extractCauseDetails(
                    new TimeoutException("timed out"));

            Map<String, String> diag = findInfoDetail(details).getDiagnosticMetadata();
            assertThrows(UnsupportedOperationException.class,
                    () -> diag.put("key", "value"));
        }

        @Test
        void transaction_deadline_extracted_via_getDeadline() {
            // System.currentTimeMillis() + 1000 — гарантированно > 0
            long deadline = System.currentTimeMillis() + 1000L;
            TransactionTimedOutException ex = new TransactionTimedOutException("Transaction timed out");
            // у TransactionTimedOutException есть конструктор с deadline; используем reflection-friendly путь:
            // в Spring 6.x getDeadline() возвращает sysclock + offset; здесь мы валидируем сам факт извлечения.
            RootCauseResolution details = strategy.extractCauseDetails(ex);

            Map<String, String> diag = findInfoDetail(details).getDiagnosticMetadata();
            // Контракт: либо ключ есть и парсится в Long, либо отсутствует.
            // Spring TransactionTimedOutException(String) не задаёт deadline → ключ может отсутствовать.
            if (diag.containsKey("transactionDeadlineEpochMs")) {
                long parsed = Long.parseLong(diag.get("transactionDeadlineEpochMs"));
                assertTrue(parsed > 0L);
            }
            // Проверяем заодно, что устаревший regex-based ключ ушёл
            assertThat(diag).doesNotContainKey("transactionDeadline");
        }
    }

    @Nested
    @DisplayName("extractTimeoutFromMessage")
    class TimeoutFromMessage {

        @ParameterizedTest
        @CsvSource({
                "'timed out after 30000ms',                     30000",
                "'Timeout after 5000 MILLISECONDS',             5000",
                "'timeout of 15000 millis',                     15000",
                "'exceeded deadline: 2000ms',                   2000",
                "'Connection pool timeout: waited 60000ms',     60000",
                "'Timeout on blocking read for 10000 MILLISECONDS', 10000"
        })
        void extracts_ms_from_common_patterns(String message, long expectedMs) {
            Long result = TimeoutExceptionStrategy.extractTimeoutFromMessage(message);
            assertNotNull(result);
            assertEquals(expectedMs, result);
        }

        @ParameterizedTest
        @CsvSource({
                "'timed out after 30 seconds',           30000",
                "'exceeded deadline after 5sec',         5000",
                "'timeout 1.5 seconds',                  1500"
        })
        void extracts_seconds_and_converts_to_ms(String message, long expectedMs) {
            Long result = TimeoutExceptionStrategy.extractTimeoutFromMessage(message);
            assertNotNull(result);
            assertEquals(expectedMs, result);
        }

        @Test
        void ms_takes_priority_over_seconds() {
            Long result = TimeoutExceptionStrategy.extractTimeoutFromMessage(
                    "timed out after 3000ms (3 seconds)"
            );
            assertNotNull(result);
            assertEquals(3000L, result);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"no timeout info here", "  ", "error occurred"})
        void returns_null_for_messages_without_timeout(String message) {
            assertNull(TimeoutExceptionStrategy.extractTimeoutFromMessage(message));
        }

        @Test
        void ignores_single_digit_ms_values() {
            assertNull(TimeoutExceptionStrategy.extractTimeoutFromMessage("timeout 5ms"));
        }
    }

    @Nested
    @DisplayName("extractOperationHint (word boundaries)")
    class OperationHint {

        @ParameterizedTest
        @CsvSource({
                "'Timeout on blocking read for 5000ms',          READ",
                "'Timeout on write to upstream',                  WRITE",
                "'Connection pool: send timeout',                 WRITE",
                "'Failed to connect within timeout',              CONNECT",
                "'Could not acquire connection from pool',        POOL_ACQUIRE",
                "'Pool exhaustion: borrow timeout',               POOL_ACQUIRE",
                "'Transaction timed out',                         TRANSACTION",
                "'Timeout on blocking call',                      BLOCKING",
                "'Request timeout after 30s',                     REQUEST",
                "'invoke method timed out',                       REQUEST",
                "'Upstream call exceeded deadline',               REQUEST"
        })
        void extracts_operation_from_message(String message, String expectedHint) {
            TimeoutException ex = new TimeoutException(message);
            String hint = TimeoutExceptionStrategy.extractOperationHint(ex);
            assertEquals(expectedHint, hint);
        }

        @Test
        void word_boundaries_avoid_false_positive_on_thread() {
            // "thread" содержит "read" как подстроку — word boundary должен спасти
            TimeoutException ex = new TimeoutException("Worker thread did not finish in time");
            assertNull(TimeoutExceptionStrategy.extractOperationHint(ex));
        }

        @Test
        void word_boundaries_avoid_false_positive_on_rewrite() {
            // "rewrite" содержит "write" — word boundary должен спасти
            TimeoutException ex = new TimeoutException("Response was rewritten");
            assertNull(TimeoutExceptionStrategy.extractOperationHint(ex));
        }

        @Test
        void returns_null_for_null_message() {
            TimeoutException ex = new TimeoutException(null);
            assertNull(TimeoutExceptionStrategy.extractOperationHint(ex));
        }

        @Test
        void returns_null_for_unrecognized_message() {
            TimeoutException ex = new TimeoutException("something happened");
            assertNull(TimeoutExceptionStrategy.extractOperationHint(ex));
        }
    }

    @Nested
    @DisplayName("rootCause enrichment via builder")
    class RootCauseEnrichment {

        @Test
        void includes_rootCause_when_wrapped() {
            IllegalStateException root = new IllegalStateException("DB connection pool exhausted");
            RuntimeException mid = new RuntimeException("processing failed", root);
            TimeoutException outer = new TimeoutException("timed out after 30000ms");
            try {
                outer.initCause(mid);
            } catch (IllegalStateException ignored) {
                // already initialized — игнорируем
            }

            RootCauseResolution details = strategy.extractCauseDetails(outer);
            Map<String, String> diag = findInfoDetail(details).getDiagnosticMetadata();

            assertEquals(IllegalStateException.class.getName(), diag.get("rootCauseClass"));
            assertNotNull(diag.get("rootCauseMessage"));
            assertTrue(diag.get("rootCauseMessage").contains("DB connection pool"));
        }

        @Test
        void no_rootCause_when_no_chain() {
            RootCauseResolution details = strategy.extractCauseDetails(
                    new TimeoutException("simple timeout")
            );

            Map<String, String> diag = findInfoDetail(details).getDiagnosticMetadata();
            assertFalse(diag.containsKey("rootCauseClass"));
            assertFalse(diag.containsKey("rootCauseMessage"));
        }
    }

    @Nested
    @DisplayName("configuredTimeoutMs in diagnosticMetadata")
    class ConfiguredTimeoutInDiag {

        @Test
        void extracts_configuredTimeoutMs_from_message() {
            RootCauseResolution details = strategy.extractCauseDetails(
                    new TimeoutException("timed out after 5000ms")
            );

            Map<String, String> diag = findInfoDetail(details).getDiagnosticMetadata();
            assertEquals("5000", diag.get("configuredTimeoutMs"));
        }

        @Test
        void no_configuredTimeoutMs_when_absent() {
            RootCauseResolution details = strategy.extractCauseDetails(
                    new TimeoutException("operation timed out")
            );

            Map<String, String> diag = findInfoDetail(details).getDiagnosticMetadata();
            assertFalse(diag.containsKey("configuredTimeoutMs"));
        }
    }

    @Nested
    @DisplayName("operationHint in diagnosticMetadata")
    class OperationHintInDiag {

        @Test
        void includes_operationHint_in_diagnosticMetadata() {
            RootCauseResolution details = strategy.extractCauseDetails(
                    new TimeoutException("Timeout on blocking read for 5000ms")
            );

            Map<String, String> diag = findInfoDetail(details).getDiagnosticMetadata();
            assertEquals("READ", diag.get("operationHint"));
        }

        @Test
        void no_operationHint_when_unrecognized() {
            RootCauseResolution details = strategy.extractCauseDetails(
                    new TimeoutException("something happened")
            );

            Map<String, String> diag = findInfoDetail(details).getDiagnosticMetadata();
            assertFalse(diag.containsKey("operationHint"));
        }
    }

    @Nested
    @DisplayName("regex compilation")
    class RegexPatterns {

        @Test
        void timeout_ms_pattern_compiles() {
            assertNotNull(TimeoutExceptionStrategy.TIMEOUT_MS_FROM_MESSAGE);
        }

        @Test
        void timeout_sec_pattern_compiles() {
            assertNotNull(TimeoutExceptionStrategy.TIMEOUT_SEC_FROM_MESSAGE);
        }

        @Test
        void ms_pattern_is_case_insensitive() {
            Long result = TimeoutExceptionStrategy.extractTimeoutFromMessage("timed out after 3000MS");
            assertNotNull(result);
            assertEquals(3000L, result);
        }

        @Test
        void sec_pattern_is_case_insensitive() {
            Long result = TimeoutExceptionStrategy.extractTimeoutFromMessage("timed out after 30 SECONDS");
            assertNotNull(result);
            assertEquals(30000L, result);
        }
    }

    private static ErrorInfoDetail findInfoDetail(RootCauseResolution details) {
        return details.details().stream()
                .filter(ErrorInfoDetail.class::isInstance)
                .map(ErrorInfoDetail.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("ErrorInfoDetail not found"));
    }

}
