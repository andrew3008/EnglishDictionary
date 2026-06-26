package space.br1440.platform.errorhandling.core.strategy.cause.core;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.extension.Retryability;
import space.br1440.platform.errorhandling.extension.RootCauseResolution;
import space.br1440.platform.errorhandling.exception.AbstractErrorResponseException;
import space.br1440.platform.errorhandling.exception.business.BusinessException;
import space.br1440.platform.errorhandling.http.HttpStatusResolver;
import space.br1440.platform.errorhandling.model.error_detail.ErrorDetail;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Контрактные тесты {@link ErrorEntryCauseStrategy}.
 *
 * <p>Покрытие: {@link PropagationMetadataKeys} + propagation-merge-политика
 * + CWE-209/CWE-117 hardening + HTTP status resolution tracking
 * + cross-service propagation (nestedDomain → originatingService).
 */
class ErrorEntryCauseStrategyTest {

    private static final String APPLICATION_NAME = "order-service";

    private final HttpStatusResolver resolver =
            exception -> HttpStatus.NOT_FOUND;

    private final ErrorEntryCauseStrategy strategy = new ErrorEntryCauseStrategy(
            resolver,
            new StackHintExtractor(true, List.of()),
            APPLICATION_NAME);

    /**
     * Узкий тестовый наследник {@link AbstractErrorResponseException} с управляемым {@code clientMetadata}.
     * HTTP-маркеры не используются — статус подставляет {@link HttpStatusResolver}-заглушка (всегда 404).
     */
    private static final class TestNotFoundException extends AbstractErrorResponseException {
        TestNotFoundException(String message) {
            super(message, Map.of());
        }

        TestNotFoundException(String message, Map<String, String> clientMeta) {
            super(message, clientMeta);
        }
    }

    /**
     * Тестовый наследник {@link BusinessException} — должен исключаться из {@code supportsCause()}.
     * Бизнес-исключения обрабатываются на top-уровне и в cause-цепочке не подхватываются.
     */
    private static final class TestBusinessException extends BusinessException {
        TestBusinessException(String message) {
            super(message, Map.of());
        }
    }

    // ---------------------------------------------------------------------------------------
    // supportsCause
    // ---------------------------------------------------------------------------------------

    @Nested
    class SupportsCause {

        @Test
        void supports_non_business_AbstractErrorResponseException_descendant() {
            assertTrue(strategy.supportsCause(new TestNotFoundException("resource missing")));
        }

        @Test
        void does_not_support_BusinessException_in_cause_chain() {
            // BusinessException — top-level контракт; вложенный BusinessException
            // не должен подхватываться cause-стратегией (намеренный fallthrough).
            assertFalse(strategy.supportsCause(new TestBusinessException("rule violated")),
                    "BusinessException обрабатывается отдельной top-стратегией, в cause-слое не поддерживается");
        }

        @Test
        void does_not_support_plain_RuntimeException() {
            assertFalse(strategy.supportsCause(new RuntimeException("x")));
        }

        @Test
        void does_not_support_plain_IllegalStateException() {
            assertFalse(strategy.supportsCause(new IllegalStateException("x")));
        }
    }

    // ---------------------------------------------------------------------------------------
    // HTTP status resolution: nestedEntry создаёт 500, resolver возвращает 404.
    // Итоговый статус — единственный источник правды (дубль в metadata не пишется).
    // ---------------------------------------------------------------------------------------

    @Nested
    class HttpStatusResolution {

        @Test
        void resolves_http_status_via_resolver_not_nestedEntry() {
            TestNotFoundException ex = new TestNotFoundException("not found");

            RootCauseResolution res = strategy.extractCauseDetails(ex);

            assertEquals(HttpStatus.NOT_FOUND, res.httpStatus(),
                    "HTTP-статус должен браться от resolver, а не от nestedEntry (500 placeholder)");
        }

        @Test
        void does_not_duplicate_http_status_in_diagnostic_metadata() {
            TestNotFoundException ex = new TestNotFoundException("not found");

            Map<String, String> diag = firstInfoDetail(strategy.extractCauseDetails(ex)).getDiagnosticMetadata();

            assertFalse(diag.containsKey("nestedHttpStatus"),
                    "nestedHttpStatus не должен писаться: это константа 500 (placeholder AbstractErrorResponseException)");
            assertFalse(diag.containsKey("resolvedHttpStatus"),
                    "resolvedHttpStatus не должен писаться: итоговый статус есть в RootCauseResolution.httpStatus()");
        }
    }

    // ---------------------------------------------------------------------------------------
    // Client metadata: propagation marker + propagatedErrorCode (namespace-защита от коллизии)
    // ---------------------------------------------------------------------------------------

    @Nested
    class ClientMetadata {

        @Test
        void marks_propagation_for_client() {
            TestNotFoundException ex = new TestNotFoundException("not found");

            ErrorInfoDetail info = firstInfoDetail(strategy.extractCauseDetails(ex));

            assertEquals(PropagationMetadataKeys.TRUE,
                    info.getClientMetadata().get(PropagationMetadataKeys.PROPAGATED));
        }

        @Test
        void exposes_propagated_error_code_under_namespaced_key() {
            TestNotFoundException ex = new TestNotFoundException("not found");

            ErrorInfoDetail info = firstInfoDetail(strategy.extractCauseDetails(ex));

            // errorCode автогенерируется: TestNotFoundException → TEST_NOT_FOUND
            assertEquals("TEST_NOT_FOUND",
                    info.getClientMetadata().get(PropagationMetadataKeys.PROPAGATED_ERROR_CODE));
        }

        @Test
        void propagation_markers_protected_from_overwrite_by_nested_client_metadata() {
            // nested detail уже имеет собственный "propagated" — protected-ключ должен быть перезаписан
            TestNotFoundException ex = new TestNotFoundException("not found", Map.of(
                    PropagationMetadataKeys.PROPAGATED, "false"
            ));

            ErrorInfoDetail info = firstInfoDetail(strategy.extractCauseDetails(ex));

            assertEquals(PropagationMetadataKeys.TRUE,
                    info.getClientMetadata().get(PropagationMetadataKeys.PROPAGATED),
                    "Маркер propagation — protected: не должен теряться при случайной коллизии");
        }
    }

    // ---------------------------------------------------------------------------------------
    // CWE-209: сообщение root-cause НЕ попадает в metadata. CWE-117: санитизация CR/LF.
    // ---------------------------------------------------------------------------------------

    @Nested
    class Security {

        @Test
        void sanitizes_crlf_in_cause_message() {
            TestNotFoundException ex = new TestNotFoundException(
                    "line1\r\nFAKE_LOG_LINE something"
            );

            ErrorInfoDetail info = firstInfoDetail(strategy.extractCauseDetails(ex));
            String causeMessage = info.getDiagnosticMetadata().get(PropagationMetadataKeys.CAUSE_MESSAGE);

            assertNotNull(causeMessage);
            assertFalse(causeMessage.contains("\r"), "CR должен быть удалён (CWE-117)");
            assertFalse(causeMessage.contains("\n"), "LF должен быть удалён (CWE-117)");
        }

        @Test
        void sanitizes_unicode_bidi_override_in_client_message() {
            // CVE-2021-42574 Trojan Source: RIGHT-TO-LEFT OVERRIDE должен быть удалён
            String raw = "order \u202Efailed\u202C status";
            TestNotFoundException ex = new TestNotFoundException(raw);

            RootCauseResolution res = strategy.extractCauseDetails(ex);

            assertFalse(res.message().contains("\u202E"),
                    "RLO должен быть удалён из клиентского message (CVE-2021-42574)");
            assertFalse(res.message().contains("\u202C"),
                    "PDF должен быть удалён из клиентского message");
        }

        @Test
        void does_not_leak_root_cause_message_to_diagnostic_metadata() {
            // Cause chain: ex -> jdbcError("select * from users where token=secret-abc")
            RuntimeException jdbcError = new RuntimeException("select * from users where token=secret-abc");
            TestNotFoundException ex = new TestNotFoundException("not found");
            ex.initCause(jdbcError);

            Map<String, String> diag = firstInfoDetail(strategy.extractCauseDetails(ex)).getDiagnosticMetadata();

            assertFalse(diag.values().stream().anyMatch(v -> v != null && v.contains("secret-abc")),
                    "CWE-209: сообщение root-cause не должно попадать в metadata");
            assertEquals(RuntimeException.class.getName(), diag.get("rootCauseClass"),
                    "FQCN root-cause — допустимо (агрегация без раскрытия деталей)");
        }

        @Test
        void does_not_leak_fqcn_of_exception_class_into_client_message() {
            // Regression guard: ранее fallback был ex.getClass().getName() (CWE-209).
            // Даже если платформа подставит default-placeholder в nestedEntry.message,
            // итоговое клиентское сообщение не должно содержать имя класса-исключения.
            class VerySpecificNotFoundException extends AbstractErrorResponseException {
                VerySpecificNotFoundException() {
                    super("", Map.of());
                }
            }

            RootCauseResolution res = strategy.extractCauseDetails(new VerySpecificNotFoundException());

            assertFalse(res.message().contains("VerySpecificNotFoundException"),
                    "Клиентское message не должно содержать FQCN класса исключения (CWE-209)");
            assertFalse(res.message().contains(VerySpecificNotFoundException.class.getName()),
                    "Клиентское message не должно содержать FQCN класса исключения (CWE-209)");
        }

        @Test
        void truncates_long_cause_message() {
            String longMsg = "x".repeat(ErrorEntryCauseStrategy.MAX_CAUSE_MESSAGE_LENGTH + 100);
            TestNotFoundException ex = new TestNotFoundException(longMsg);

            ErrorInfoDetail info = firstInfoDetail(strategy.extractCauseDetails(ex));
            String causeMessage = info.getDiagnosticMetadata().get(PropagationMetadataKeys.CAUSE_MESSAGE);

            assertNotNull(causeMessage);
            assertTrue(causeMessage.length() <= ErrorEntryCauseStrategy.MAX_CAUSE_MESSAGE_LENGTH + 16,
                    "LogSanitizer должен обрезать сообщение до MAX_CAUSE_MESSAGE_LENGTH (+ маркер усечения)");
        }
    }

    // ---------------------------------------------------------------------------------------
    // Cross-service propagation: service-name попадает в observability-слой (OTel/MDC),
    // а не в metadata. Diagnostic metadata не содержит sourceService/originatingService.
    // ---------------------------------------------------------------------------------------

    @Nested
    class CrossServicePropagation {

        @Test
        void does_not_write_service_name_into_metadata() {
            TestNotFoundException ex = new TestNotFoundException("not found");

            Map<String, String> diag = firstInfoDetail(strategy.extractCauseDetails(ex)).getDiagnosticMetadata();

            assertFalse(diag.containsKey("sourceService"),
                    "service.name — из OpenTelemetry Resource / MDC, а не из кастомного ключа");
            assertFalse(diag.containsKey("originatingService"),
                    "multi-hop trail — через traceparent (W3C), а не через кастомный ключ");
        }
    }

    // ---------------------------------------------------------------------------------------
    // Retryability: nestedEntry без RetryInfoDetail → UNSPECIFIED (не NOT_RETRYABLE).
    // ---------------------------------------------------------------------------------------

    @Nested
    class RetryabilityDerivation {

        @Test
        void defaults_to_unspecified_when_no_retry_info() {
            TestNotFoundException ex = new TestNotFoundException("not found");

            RootCauseResolution res = strategy.extractCauseDetails(ex);

            assertEquals(Retryability.UNSPECIFIED, res.retryability());
        }
    }

    // ---------------------------------------------------------------------------------------
    // ErrorInfoDetail merge: существующий detail enriched (не удаляется),
    // immutability сохранена.
    // ---------------------------------------------------------------------------------------

    @Nested
    class DetailMerge {

        @Test
        void enriches_existing_ErrorInfoDetail_in_place() {
            // TestNotFoundException создаёт 1 ErrorInfoDetail с reason = "TEST_NOT_FOUND"
            TestNotFoundException ex = new TestNotFoundException("not found");

            RootCauseResolution res = strategy.extractCauseDetails(ex);

            assertEquals(1, res.details().size(), "не должен дублировать ErrorInfoDetail");
            assertEquals("TEST_NOT_FOUND", ((ErrorInfoDetail) res.details().get(0)).getReason());
        }

        @Test
        void produces_new_detail_instance_not_mutating_original() {
            TestNotFoundException ex = new TestNotFoundException("not found");
            ErrorInfoDetail original = (ErrorInfoDetail) ex.getErrorEntry().getDetails().get(0);

            RootCauseResolution res = strategy.extractCauseDetails(ex);
            ErrorInfoDetail enriched = (ErrorInfoDetail) res.details().get(0);

            assertNotSame(original, enriched,
                    "Мы не мутируем nested detail, а создаём обогащённую копию (immutability)");
            assertFalse(original.getClientMetadata().containsKey(PropagationMetadataKeys.PROPAGATED),
                    "оригинал nested detail остался немодифицированным");
        }
    }

    // ---------------------------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------------------------

    private static ErrorInfoDetail firstInfoDetail(RootCauseResolution res) {
        for (ErrorDetail d : res.details()) {
            if (d instanceof ErrorInfoDetail info) {
                return info;
            }
        }
        throw new AssertionError("Expected at least one ErrorInfoDetail in RootCauseResolution");
    }
}
