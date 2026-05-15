package space.br1440.platform.errorhandling.core.strategy.cause.core;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.extension.Retryability;
import space.br1440.platform.errorhandling.extension.RootCauseResolution;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.error_detail.ErrorDetail;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;
import space.br1440.platform.errorhandling.extension.LogPolicy;

import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class IllegalStateExceptionStrategyTest {

    private final IllegalStateExceptionStrategy strategy =
            new IllegalStateExceptionStrategy(new StackHintExtractor(true, List.of()));

    // ---------------------------------------------------------------------------------------
    // supportsCause: строго только pure IllegalStateException
    // ---------------------------------------------------------------------------------------

    @Nested
    class SupportsCause {

        @Test
        void supports_pure_IllegalStateException() {
            assertTrue(strategy.supportsCause(new IllegalStateException("Order already submitted")));
        }

        @Test
        void does_not_support_CancellationException_subclass() {
            assertFalse(strategy.supportsCause(new CancellationException("cancelled")),
                    "CancellationException имеет иную семантику (отмена, не конфликт состояния) — " +
                            "должна обрабатываться отдельной стратегией либо fallback на 500");
        }

        @Test
        void does_not_support_ConcurrentModificationException_subclass() {
            assertFalse(strategy.supportsCause(new ConcurrentModificationException("race")),
                    "CME — признак server-side бага (нарушение контракта итератора); " +
                            "не должен превращаться в 409 Conflict");
        }

        @Test
        void does_not_support_arbitrary_IllegalStateException_subclass() {
            class CustomStateError extends IllegalStateException {
                CustomStateError(String msg) { super(msg); }
            }
            assertFalse(strategy.supportsCause(new CustomStateError("x")),
                    "Наследники ISE могут кодировать доменные/технические ошибки с иной HTTP-семантикой — " +
                            "должны обрабатываться отдельными стратегиями");
        }

        @Test
        void does_not_support_IllegalArgumentException() {
            assertFalse(strategy.supportsCause(new IllegalArgumentException("bad arg")));
        }

        @Test
        void does_not_support_RuntimeException() {
            assertFalse(strategy.supportsCause(new RuntimeException("generic")));
        }
    }

    // ---------------------------------------------------------------------------------------
    // HTTP mapping: 409 Conflict
    // ---------------------------------------------------------------------------------------

    @Nested
    class HttpMapping {

        @Test
        void maps_to_409_Conflict() {
            RootCauseResolution res =
                    strategy.extractCauseDetails(new IllegalStateException("conflict"));

            assertEquals(HttpStatus.CONFLICT, res.httpStatus(),
                    "IllegalStateException как нарушение инварианта должна мапиться в 409 Conflict (RFC 9110 §15.5.10), " +
                            "а не в 422 или 400");
        }

        @Test
        void uses_neutral_client_message_not_exception_message() {
            IllegalStateException ex = new IllegalStateException("Internal pool=auth-pool id=42");

            RootCauseResolution res = strategy.extractCauseDetails(ex);

            assertEquals(IllegalStateExceptionStrategy.MESSAGE, res.message(),
                    "Клиенту должно уходить нейтральное сообщение (CWE-209); " +
                            "сообщение исключения может содержать внутренние идентификаторы");
        }

        @Test
        void declares_NOT_RETRYABLE_explicitly() {
            RootCauseResolution res =
                    strategy.extractCauseDetails(new IllegalStateException("Order already submitted"));

            assertEquals(Retryability.NOT_RETRYABLE, res.retryability(),
                    "Конфликт состояния детерминирован: повтор без изменения состояния даст тот же 409. " +
                            "Явная декларация NOT_RETRYABLE нужна SDK-генерации и retry-клиентам, " +
                            "Retryability.UNSPECIFIED был бы неоднозначным");
        }
    }

    // ---------------------------------------------------------------------------------------
    // ErrorInfoDetail: reason/domain
    // ---------------------------------------------------------------------------------------

    @Nested
    class ReasonAndDomain {

        @Test
        void reason_is_INVALID_STATE() {
            ErrorInfoDetail info = extractInfo(strategy.extractCauseDetails(
                    new IllegalStateException("x")));
            assertEquals("INVALID_STATE", info.getReason());
        }

        @Test
        void domain_is_state_not_generic_application() {
            ErrorInfoDetail info = extractInfo(strategy.extractCauseDetails(
                    new IllegalStateException("x")));
            assertEquals("state", info.getDomain(),
                    "domain=\"state\" позволяет агрегировать метрики по семантике (state-conflict), " +
                            "тогда как \"application\" был слишком обобщённым");
        }
    }

    // ---------------------------------------------------------------------------------------
    // Диагностические метаданные
    // ---------------------------------------------------------------------------------------

    @Nested
    class DiagnosticMetadata {

        @Test
        void contains_stackHint_without_redundant_exceptionClass() {
            IllegalStateException ex = new IllegalStateException("Order already submitted");
            Map<String, String> diag = extractInfo(strategy.extractCauseDetails(ex))
                    .getDiagnosticMetadata();

            assertTrue(diag.containsKey("stackHint"));
            assertFalse(diag.containsKey("exceptionClass"),
                    "exceptionClass избыточен: после сужения supportsCause до pure ISE значение всегда " +
                            "java.lang.IllegalStateException — та же информация уже есть в reason/domain");
        }

        @Test
        void records_causeMessage_when_present() {
            IllegalStateException ex = new IllegalStateException("Cart is empty");
            Map<String, String> diag = extractInfo(strategy.extractCauseDetails(ex))
                    .getDiagnosticMetadata();

            assertEquals("Cart is empty", diag.get("causeMessage"));
        }

        @Test
        void omits_causeMessage_when_blank() {
            IllegalStateException ex = new IllegalStateException("");
            Map<String, String> diag = extractInfo(strategy.extractCauseDetails(ex))
                    .getDiagnosticMetadata();

            assertFalse(diag.containsKey("causeMessage"),
                    "пустое сообщение не должно засорять metadata");
        }

        @Test
        void omits_causeMessage_when_null() {
            IllegalStateException ex = new IllegalStateException();
            Map<String, String> diag = extractInfo(strategy.extractCauseDetails(ex))
                    .getDiagnosticMetadata();

            assertFalse(diag.containsKey("causeMessage"));
        }
    }

    // ---------------------------------------------------------------------------------------
    // Безопасность: CWE-117 / CWE-209 / "Trojan Source"
    // ---------------------------------------------------------------------------------------

    @Nested
    class Security {

        @Test
        void sanitizes_CRLF_in_causeMessage() {
            IllegalStateException ex =
                    new IllegalStateException("State A\r\nINFO fake log line injected");

            Map<String, String> diag = extractInfo(strategy.extractCauseDetails(ex))
                    .getDiagnosticMetadata();

            String msg = diag.get("causeMessage");
            assertNotNull(msg);
            assertFalse(msg.contains("\r"), "CR должен быть удалён (CWE-117)");
            assertFalse(msg.contains("\n"), "LF должен быть удалён (CWE-117)");
        }

        @Test
        void sanitizes_unicode_bidi_override_in_causeMessage() {
            // U+202E — RIGHT-TO-LEFT OVERRIDE (CVE-2021-42574 "Trojan Source")
            IllegalStateException ex =
                    new IllegalStateException("legit\u202Emalicious");

            Map<String, String> diag = extractInfo(strategy.extractCauseDetails(ex))
                    .getDiagnosticMetadata();

            String msg = diag.get("causeMessage");
            assertNotNull(msg);
            assertFalse(msg.contains("\u202E"),
                    "bidirectional override должен быть удалён из логов (Trojan Source)");
        }

        @Test
        void truncates_causeMessage_to_max_length() {
            String huge = "x".repeat(IllegalStateExceptionStrategy.MAX_CAUSE_MESSAGE_LENGTH + 500);
            IllegalStateException ex = new IllegalStateException(huge);

            Map<String, String> diag = extractInfo(strategy.extractCauseDetails(ex))
                    .getDiagnosticMetadata();

            String msg = diag.get("causeMessage");
            assertNotNull(msg);
            assertTrue(msg.length()
                            <= IllegalStateExceptionStrategy.MAX_CAUSE_MESSAGE_LENGTH
                            + "[TRUNCATED]".length(),
                    "causeMessage должен быть ограничен MAX_CAUSE_MESSAGE_LENGTH + маркер");
        }

        @Test
        void does_not_include_rootCauseMessage_even_when_root_has_message() {
            IllegalArgumentException root =
                    new IllegalArgumentException("pool=primary-auth id=42 token=abc.def.ghi");
            IllegalStateException ex = new IllegalStateException("wrapper", root);

            Map<String, String> diag = extractInfo(strategy.extractCauseDetails(ex))
                    .getDiagnosticMetadata();

            assertFalse(diag.containsKey("rootCauseMessage"),
                    "root-cause message часто содержит внутренние идентификаторы " +
                            "(пулы/токены/SQL/URL) — должен быть исключён для защиты от CWE-209");
        }

        @Test
        void client_message_never_includes_exception_message() {
            String internal = "pool=auth-pool txId=tx-7bc order=order-99 user=admin@internal";
            IllegalStateException ex = new IllegalStateException(internal);

            RootCauseResolution res = strategy.extractCauseDetails(ex);

            assertFalse(res.message().contains("pool"));
            assertFalse(res.message().contains("txId"));
            assertFalse(res.message().contains("admin"));
        }
    }

    // ---------------------------------------------------------------------------------------
    // rootCauseClass: обход цепочки
    // ---------------------------------------------------------------------------------------

    @Nested
    class RootCauseNavigation {

        @Test
        void records_rootCauseClass_when_wrapped() {
            Throwable deep = new IllegalArgumentException("deep root");
            IllegalStateException ex = new IllegalStateException("wrapper", deep);

            Map<String, String> diag = extractInfo(strategy.extractCauseDetails(ex))
                    .getDiagnosticMetadata();

            assertEquals("java.lang.IllegalArgumentException", diag.get("rootCauseClass"));
        }

        @Test
        void records_deepest_root_through_multiple_layers() {
            Throwable deepest = new java.io.IOException("disk");
            Throwable mid     = new RuntimeException("wrapper-mid", deepest);
            IllegalStateException ex = new IllegalStateException("top", mid);

            Map<String, String> diag = extractInfo(strategy.extractCauseDetails(ex))
                    .getDiagnosticMetadata();

            assertEquals("java.io.IOException", diag.get("rootCauseClass"),
                    "должна извлекаться самая глубокая причина, а не прямой getCause()");
        }

        @Test
        void omits_rootCauseClass_when_no_cause() {
            IllegalStateException ex = new IllegalStateException("no-wrap");

            Map<String, String> diag = extractInfo(strategy.extractCauseDetails(ex))
                    .getDiagnosticMetadata();

            assertFalse(diag.containsKey("rootCauseClass"),
                    "без вложенной причины ключ не должен добавляться");
        }

        @Test
        void rootCauseClass_is_capped_to_MAX_ROOT_CAUSE_CLASS_LENGTH() {
            // Контракт: FQCN root-cause проходит через LogSanitizer с лимитом
            // MAX_ROOT_CAUSE_CLASS_LENGTH (защита от длинных имён прокси-классов
            // CGLIB/ByteBuddy: Foo$$EnhancerByCGLIB$$abcd...). Для обычного FQCN
            // ограничение прозрачно — он целиком умещается без маркера усечения.
            IllegalStateException ex =
                    new IllegalStateException("wrapper", new IllegalArgumentException("inner"));

            Map<String, String> diag = extractInfo(strategy.extractCauseDetails(ex))
                    .getDiagnosticMetadata();

            String rootCauseClass = diag.get("rootCauseClass");
            assertNotNull(rootCauseClass);
            assertTrue(rootCauseClass.length()
                            <= IllegalStateExceptionStrategy.MAX_ROOT_CAUSE_CLASS_LENGTH
                            + " [TRUNCATED]".length(),
                    "rootCauseClass должен быть ограничен MAX_ROOT_CAUSE_CLASS_LENGTH + маркер: "
                            + rootCauseClass);
            assertFalse(rootCauseClass.contains("[TRUNCATED]"),
                    "короткий FQCN не должен модифицироваться санитайзером");
        }
    }

    // ---------------------------------------------------------------------------------------
    // LogPolicy: WARN
    // ---------------------------------------------------------------------------------------

    @Nested
    class LogPolicyTest {

        @Test
        void uses_WARN_level() {
            assertEquals(LogPolicy.WARN,
                    strategy.logPolicy(new IllegalStateException("x"), mock(ErrorEntry.class)),
                    "нарушение бизнес-инварианта ожидаемо в доменной логике — WARN, не ERROR");
        }
    }

    // ---------------------------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------------------------

    private static ErrorInfoDetail extractInfo(RootCauseResolution resolution) {
        List<ErrorDetail> details = resolution.details();
        assertEquals(1, details.size());
        return (ErrorInfoDetail) details.get(0);
    }
}
