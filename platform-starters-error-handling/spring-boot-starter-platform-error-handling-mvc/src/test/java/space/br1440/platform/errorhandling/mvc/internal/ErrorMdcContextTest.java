package space.br1440.platform.errorhandling.mvc.internal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import space.br1440.platform.errorhandling.core.constants.ErrorHandlingMdcKeys;
import space.br1440.platform.errorhandling.model.ErrorEntry;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ErrorMdcContext")
class ErrorMdcContextTest {

    private static final String CORRELATION_ID = "test-corr-1";

    @BeforeEach
    void clearMdc() {
        MDC.clear();
    }

    @AfterEach
    void cleanup() {
        MDC.clear();
    }

    @Nested
    @DisplayName("Жизненный цикл try-with-resources")
    class Lifecycle {

        @Test
        @DisplayName("После close() все управляемые ключи удалены")
        void close_removesAllManagedKeys() {
            // given
            try (ErrorMdcContext ctx = ErrorMdcContext.open(CORRELATION_ID, new RuntimeException())) {
                // when
                ctx.enrichWithEntry(sampleEntry());

                // then
                assertThat(MDC.get(ErrorHandlingMdcKeys.CORRELATION_ID)).isEqualTo(CORRELATION_ID);
                assertThat(MDC.get(ErrorHandlingMdcKeys.ERROR_CODE)).isEqualTo("VALIDATION");
            }

            // then
            assertThat(MDC.get(ErrorHandlingMdcKeys.CORRELATION_ID)).isNull();
            assertThat(MDC.get(ErrorHandlingMdcKeys.EXCEPTION_TYPE)).isNull();
            assertThat(MDC.get(ErrorHandlingMdcKeys.ERROR_STATUS)).isNull();
            assertThat(MDC.get(ErrorHandlingMdcKeys.ERROR_CODE)).isNull();
            assertThat(MDC.get(ErrorHandlingMdcKeys.ERROR_MESSAGE)).isNull();
            assertThat(MDC.get(ErrorHandlingMdcKeys.ERROR_REASON)).isNull();
        }

        @Test
        @DisplayName("Внешние ключи (traceId, userId) НЕ затрагиваются")
        void externalKeys_preserved() {
            // given
            MDC.put("traceId", "ext-trace");
            MDC.put("userId", "ext-user");

            // when
            // Тело try намеренно пустое: проверяется только жизненный цикл open/close
            // ErrorMdcContext, без enrichWithEntry. close() не должен трогать внешние ключи MDC.
            //noinspection EmptyTryBlock
            try (ErrorMdcContext ignored = ErrorMdcContext.open(CORRELATION_ID, new RuntimeException())) {
            }

            // then
            assertThat(MDC.get("traceId")).isEqualTo("ext-trace");
            assertThat(MDC.get("userId")).isEqualTo("ext-user");
        }

        @Test
        @DisplayName("close() идемпотентен — повторный вызов не падает")
        void close_idempotent() {
            // given
            ErrorMdcContext ctx = ErrorMdcContext.open(CORRELATION_ID, new RuntimeException());

            // when
            ctx.close();
            ctx.close();

            // then
            assertThat(MDC.get(ErrorHandlingMdcKeys.CORRELATION_ID)).isNull();
        }
    }

    @Nested
    @DisplayName("putSafe — null-safe запись")
    class PutSafe {

        @Test
        @DisplayName("null-значение НЕ кидает (защита от SLF4J 2.x IAE)")
        void nullValue_silentlySkipped() {
            // when
            ErrorMdcContext.putSafe("anyKey", null);

            // then
            assertThat(MDC.get("anyKey")).isNull();
        }

        @Test
        @DisplayName("Реальное значение записывается")
        void nonNull_written() {
            try {
                // when
                ErrorMdcContext.putSafe("k", "v");

                // then
                assertThat(MDC.get("k")).isEqualTo("v");
            } finally {
                MDC.remove("k");
            }
        }
    }

    @Nested
    @DisplayName("enrichWithEntry — заполнение MDC из ErrorEntry")
    class EnrichWithEntry {

        @Test
        @DisplayName("error_status, error_code, error_message заполняются из entry")
        void mandatoryFields_filled() {
            // given
            try (ErrorMdcContext ctx = ErrorMdcContext.open(CORRELATION_ID, new RuntimeException())) {
                // when
                ctx.enrichWithEntry(sampleEntry());

                // then
                assertThat(MDC.get(ErrorHandlingMdcKeys.ERROR_STATUS)).isEqualTo("400");
                assertThat(MDC.get(ErrorHandlingMdcKeys.ERROR_CODE)).isEqualTo("VALIDATION");
                assertThat(MDC.get(ErrorHandlingMdcKeys.ERROR_MESSAGE)).isEqualTo("invalid");
            }
        }

        @Test
        @DisplayName("error_reason записывается только при наличии непустого значения")
        void reason_writtenOnlyWhenPresent() {
            // given
            try (ErrorMdcContext ctx = ErrorMdcContext.open(CORRELATION_ID, new RuntimeException())) {
                // when
                ctx.enrichWithEntry(sampleEntry());

                // then
                assertThat(MDC.get(ErrorHandlingMdcKeys.ERROR_REASON)).isNull();
            }
        }

        @Test
        @DisplayName("Entry с reason — записывается")
        void reason_written_whenSet() {
            // given
            ErrorEntry entry = ErrorEntry.builder()
                    .httpStatus(HttpStatus.BAD_REQUEST)
                    .errorCode("VALIDATION")
                    .message("bad")
                    .reason("FIELD_MISSING")
                    .build();
            try (ErrorMdcContext ctx = ErrorMdcContext.open(CORRELATION_ID, new RuntimeException())) {
                // when
                ctx.enrichWithEntry(entry);

                // then
                assertThat(MDC.get(ErrorHandlingMdcKeys.ERROR_REASON)).isEqualTo("FIELD_MISSING");
            }
        }
    }

    private static ErrorEntry sampleEntry() {
        return ErrorEntry.builder()
                .httpStatus(HttpStatus.BAD_REQUEST)
                .errorCode("VALIDATION")
                .message("invalid")
                .build();
    }
}
