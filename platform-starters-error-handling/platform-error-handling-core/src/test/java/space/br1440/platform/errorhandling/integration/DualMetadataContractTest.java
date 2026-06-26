package space.br1440.platform.errorhandling.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;

import java.util.List;
import java.util.Map;

import static space.br1440.platform.errorhandling.testing.assertions.ErrorHandlingAssertions.assertThat;

/**
 * Верификация dual-metadata контракта (CWE-209) без поднятия Spring-контекста.
 *
 * <p>Тесты гарантируют, что диагностическая информация (rootCause, sqlState,
 * стектрейсы) остаётся в {@code diagnosticMetadata} и не утекает
 * ни в {@code clientMetadata}, ни в публичные поля {@code message}/{@code reason}.
 */
@DisplayName("Dual-metadata контракт (CWE-209)")
class DualMetadataContractTest {

    // ── Тестовые данные ─────────────────────────────────────────────

    private static final Map<String, String> CLIENT_META = Map.of(
            "errorType", "DATABASE_ERROR",
            "errorLayer", "STORAGE"
    );

    private static final Map<String, String> DIAGNOSTIC_META = Map.of(
            "rootCauseClass", "org.postgresql.util.PSQLException",
            "rootCauseMessage", "ERROR: relation \"orders\" does not exist",
            "sqlState", "42P01",
            "stackHint", "at com.app.repo.OrderRepository.findAll(OrderRepository.java:35)"
    );

    private static ErrorEntry buildEntryWithDualMetadata() {
        ErrorInfoDetail info = ErrorInfoDetail.builder()
                .reason("Ошибка доступа к таблице")
                .clientMetadata(CLIENT_META)
                .diagnosticMetadata(DIAGNOSTIC_META)
                .build();

        return ErrorEntry.builder()
                .httpStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                .errorCode("SVC-001")
                .message("Внутренняя ошибка сервиса")
                .details(List.of(info))
                .build();
    }

    // ── clientMetadata: не содержит чувствительных данных ────────────

    @Nested
    @DisplayName("clientMetadata — безопасность")
    class ClientMetadataSafety {

        @Test
        @DisplayName("clientMetadata не содержит SQL-состояния, стектрейсы и внутренние URL")
        void clientMetadata_excludes_sensitive_keys() {
            // given
            ErrorEntry entry = buildEntryWithDualMetadata();

            // when / then
            assertThat(entry)
                    .extractingFirstErrorInfo()
                    .clientMetadataDoesNotContain(
                            "rootCauseClass",
                            "rootCauseMessage",
                            "sqlState",
                            "stackHint"
                    );
        }

        @Test
        @DisplayName("clientMetadata содержит только классификационные ключи")
        void clientMetadata_contains_classification_keys() {
            // given
            ErrorEntry entry = buildEntryWithDualMetadata();

            // when / then
            assertThat(entry)
                    .extractingFirstErrorInfo()
                    .clientMetadataContains("errorType", "DATABASE_ERROR")
                    .clientMetadataContains("errorLayer", "STORAGE");
        }
    }

    // ── diagnosticMetadata: полная диагностика ──────────────────────

    @Nested
    @DisplayName("diagnosticMetadata — полнота диагностики")
    class DiagnosticMetadataCompleteness {

        @Test
        @DisplayName("diagnosticMetadata содержит rootCauseClass и rootCauseMessage")
        void diagnosticMetadata_contains_root_cause() {
            // given
            ErrorEntry entry = buildEntryWithDualMetadata();

            // when / then
            assertThat(entry)
                    .extractingFirstErrorInfo()
                    .diagnosticMetadataContains("rootCauseClass", "org.postgresql.util.PSQLException")
                    .diagnosticMetadataContains("rootCauseMessage", "ERROR: relation \"orders\" does not exist");
        }

        @Test
        @DisplayName("diagnosticMetadata содержит sqlState и stackHint")
        void diagnosticMetadata_contains_sql_state_and_stack_hint() {
            // given
            ErrorEntry entry = buildEntryWithDualMetadata();

            // when / then
            assertThat(entry)
                    .extractingFirstErrorInfo()
                    .diagnosticMetadataContains("sqlState", "42P01")
                    .diagnosticMetadataContains("stackHint",
                            "at com.app.repo.OrderRepository.findAll(OrderRepository.java:35)");
        }
    }

    // ── CWE-209: diagnosticMetadata не утекает в clientMetadata ──────

    @Nested
    @DisplayName("CWE-209 — изоляция диагностики от клиента")
    class Cwe209Isolation {

        @Test
        @DisplayName("ни один ключ diagnosticMetadata не присутствует в clientMetadata")
        void diagnostic_keys_are_not_exposed_to_client() {
            // given
            ErrorEntry entry = buildEntryWithDualMetadata();

            // when / then
            assertThat(entry)
                    .extractingFirstErrorInfo()
                    .diagnosticMetadataIsNotExposedToClient();
        }
    }

    // ── containsNoSensitiveData: message и reason ───────────────────

    @Nested
    @DisplayName("containsNoSensitiveData — публичные поля")
    class NoSensitiveDataInPublicFields {

        @Test
        @DisplayName("message и reason не содержат IP-адреса, пароли и стектрейсы")
        void message_and_reason_are_clean() {
            // given
            ErrorEntry entry = buildEntryWithDualMetadata();

            // when / then
            assertThat(entry).containsNoSensitiveData();
        }

        @Test
        @DisplayName("clientMetadata-значения тоже проходят проверку на sensitive data")
        void client_metadata_values_are_clean() {
            // given
            ErrorInfoDetail info = ErrorInfoDetail.builder()
                    .reason("Ресурс не найден")
                    .clientMetadata(Map.of(
                            "errorType", "NOT_FOUND",
                            "resourceKind", "order"
                    ))
                    .diagnosticMetadata(Map.of(
                            "rootCauseClass", "javax.persistence.EntityNotFoundException"
                    ))
                    .build();

            ErrorEntry entry = ErrorEntry.builder()
                    .httpStatus(HttpStatus.NOT_FOUND)
                    .errorCode("SVC-0301")
                    .message("Запрашиваемый ресурс не найден")
                    .details(List.of(info))
                    .build();

            // when / then
            assertThat(entry).containsNoSensitiveData();
        }
    }

    // ── Навигация: extractingFirstErrorInfo → backToEntry ────────────

    @Nested
    @DisplayName("Навигация — цепочка assertions")
    class NavigationChain {

        @Test
        @DisplayName("extractingFirstErrorInfo → проверки → backToEntry → проверки entry")
        void full_navigation_chain() {
            // given
            ErrorEntry entry = buildEntryWithDualMetadata();

            // when / then
            assertThat(entry)
                    .hasHttpStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                    .hasErrorCode("SVC-001")
                    .extractingFirstErrorInfo()
                        .hasReason("Ошибка доступа к таблице")
                        .clientMetadataContains("errorType", "DATABASE_ERROR")
                        .diagnosticMetadataContains("sqlState", "42P01")
                        .diagnosticMetadataIsNotExposedToClient()
                    .backToEntry()
                    .containsNoSensitiveData()
                    .hasMessage("Внутренняя ошибка сервиса");
        }

        @Test
        @DisplayName("backToEntry сохраняет контекст исходного ErrorEntry")
        void backToEntry_preserves_entry_context() {
            // given
            ErrorEntry entry = buildEntryWithDualMetadata();

            // when / then
            assertThat(entry)
                    .extractingFirstErrorInfo()
                        .diagnosticMetadataIsNotExposedToClient()
                    .backToEntry()
                    .hasHttpStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                    .hasErrorCode("SVC-001")
                    .hasDetailsCount(1);
        }
    }

}
