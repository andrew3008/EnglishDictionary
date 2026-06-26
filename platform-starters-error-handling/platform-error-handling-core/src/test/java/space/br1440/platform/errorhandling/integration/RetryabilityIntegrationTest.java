package space.br1440.platform.errorhandling.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;
import space.br1440.platform.errorhandling.model.error_detail.RetryInfoDetail;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Интеграционные тесты retryable-семантики {@link ErrorEntry} на основе {@link RetryInfoDetail}.
 *
 * <p>Проверяет корректность {@code isRetryable()} и {@code getRetryDelay()}
 * для различных комбинаций деталей без поднятия Spring-контекста.
 */
@DisplayName("Retryable-семантика ErrorEntry")
class RetryabilityIntegrationTest {

    @Nested
    @DisplayName("RetryInfoDetail с указанной задержкой")
    class WithExplicitDelay {

        @Test
        @DisplayName("isRetryable() возвращает true, getRetryDelay() — заданная Duration")
        void retryable_with_explicit_delay() {
            // given
            Duration delay = Duration.ofSeconds(30);

            ErrorEntry entry = ErrorEntry.builder()
                    .httpStatus(HttpStatus.TOO_MANY_REQUESTS)
                    .errorCode("SVC-001")
                    .message("Превышен лимит запросов")
                    .details(List.of(new RetryInfoDetail(delay)))
                    .build();

            // when / then
            assertThat(entry.isRetryable()).isTrue();
            assertThat(entry.getRetryDelay()).isEqualTo(Duration.ofSeconds(30));
        }
    }

    @Nested
    @DisplayName("RetryInfoDetail без задержки")
    class WithoutDelay {

        @Test
        @DisplayName("isRetryable() возвращает true, getRetryDelay() — null")
        void retryable_without_delay() {
            // given
            ErrorEntry entry = ErrorEntry.builder()
                    .httpStatus(HttpStatus.SERVICE_UNAVAILABLE)
                    .errorCode("SVC-002")
                    .message("Сервис временно недоступен")
                    .details(List.of(new RetryInfoDetail()))
                    .build();

            // when / then
            assertThat(entry.isRetryable()).isTrue();
            assertThat(entry.getRetryDelay()).isNull();
        }
    }

    @Nested
    @DisplayName("Без RetryInfoDetail")
    class WithoutRetryInfo {

        @Test
        @DisplayName("isRetryable() возвращает false, getRetryDelay() — null (только ErrorInfoDetail)")
        void not_retryable_with_error_info_only() {
            // given
            ErrorInfoDetail info = ErrorInfoDetail.builder()
                    .reason("Ресурс не найден")
                    .build();

            ErrorEntry entry = ErrorEntry.builder()
                    .httpStatus(HttpStatus.NOT_FOUND)
                    .errorCode("SVC-003")
                    .message("Запрашиваемый ресурс не найден")
                    .details(List.of(info))
                    .build();

            // when / then
            assertThat(entry.isRetryable()).isFalse();
            assertThat(entry.getRetryDelay()).isNull();
        }

        @Test
        @DisplayName("isRetryable() возвращает false, getRetryDelay() — null (пустой details)")
        void not_retryable_with_empty_details() {
            // given
            ErrorEntry entry = ErrorEntry.builder()
                    .httpStatus(HttpStatus.BAD_REQUEST)
                    .errorCode("SVC-004")
                    .message("Некорректный запрос")
                    .build();

            // when / then
            assertThat(entry.isRetryable()).isFalse();
            assertThat(entry.getRetryDelay()).isNull();
        }
    }

    @Nested
    @DisplayName("Множественные details, включая RetryInfoDetail")
    class MultipleDetailsWithRetry {

        @Test
        @DisplayName("isRetryable() — true, getRetryDelay() берётся из первого RetryInfoDetail")
        void retryable_takes_first_retry_delay() {
            // given
            Duration firstDelay = Duration.ofSeconds(10);

            ErrorInfoDetail info = ErrorInfoDetail.builder()
                    .reason("Таймаут соединения")
                    .build();

            ErrorEntry entry = ErrorEntry.builder()
                    .httpStatus(HttpStatus.GATEWAY_TIMEOUT)
                    .errorCode("SVC-005")
                    .message("Превышено время ожидания")
                    .details(List.of(
                            info,
                            new RetryInfoDetail(firstDelay),
                            new RetryInfoDetail(Duration.ofSeconds(60))
                    ))
                    .build();

            // when / then
            assertThat(entry.isRetryable()).isTrue();
            assertThat(entry.getRetryDelay())
                    .as("задержка должна быть из первого RetryInfoDetail в списке")
                    .isEqualTo(firstDelay);
        }

        @Test
        @DisplayName("RetryInfoDetail без задержки среди других деталей — retryable, delay null")
        void retryable_no_delay_among_other_details() {
            // given
            ErrorInfoDetail info = ErrorInfoDetail.builder()
                    .reason("Circuit breaker open")
                    .build();

            ErrorEntry entry = ErrorEntry.builder()
                    .httpStatus(HttpStatus.SERVICE_UNAVAILABLE)
                    .errorCode("SVC-006")
                    .message("Сервис временно недоступен")
                    .details(List.of(info, new RetryInfoDetail()))
                    .build();

            // when / then
            assertThat(entry.isRetryable()).isTrue();
            assertThat(entry.getRetryDelay()).isNull();
        }
    }

}
