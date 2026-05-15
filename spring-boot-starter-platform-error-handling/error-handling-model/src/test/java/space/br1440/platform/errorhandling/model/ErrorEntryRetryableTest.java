package space.br1440.platform.errorhandling.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;
import space.br1440.platform.errorhandling.model.error_detail.RetryInfoDetail;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ErrorEntry — retryable contract")
class ErrorEntryRetryableTest {

    @Nested
    @DisplayName("isRetryable")
    class IsRetryable {

        @Test
        @DisplayName("null details -> false")
        void nullDetails_returnsFalse() {
            ErrorEntry entry = entryWith(null);

            assertThat(entry.isRetryable()).isFalse();
        }

        @Test
        @DisplayName("empty details -> false")
        void emptyDetails_returnsFalse() {
            ErrorEntry entry = entryWith(Collections.emptyList());

            assertThat(entry.isRetryable()).isFalse();
        }

        @Test
        @DisplayName("RetryInfoDetail present -> true")
        void withRetryInfoDetail_returnsTrue() {
            ErrorEntry entry = ErrorEntry.builder()
                    .httpStatus(HttpStatus.SERVICE_UNAVAILABLE)
                    .errorCode("UNAVAILABLE")
                    .message("unavailable")
                    .details(List.of(new RetryInfoDetail()))
                    .build();

            assertThat(entry.isRetryable()).isTrue();
        }

        @Test
        @DisplayName("only ErrorInfoDetail, no RetryInfoDetail -> false")
        void withOnlyErrorInfoDetail_returnsFalse() {
            ErrorEntry entry = ErrorEntry.builder()
                    .httpStatus(HttpStatus.NOT_FOUND)
                    .errorCode("NOT_FOUND")
                    .message("not found")
                    .details(List.of(ErrorInfoDetail.builder().build()))
                    .build();

            assertThat(entry.isRetryable()).isFalse();
        }
    }

    @Nested
    @DisplayName("getRetryDelay")
    class GetRetryDelay {

        @Test
        @DisplayName("RetryInfoDetail with duration -> returns that duration")
        void withDuration_returnsDuration() {
            Duration delay = Duration.ofSeconds(5);
            ErrorEntry entry = ErrorEntry.builder()
                    .httpStatus(HttpStatus.TOO_MANY_REQUESTS)
                    .errorCode("RESOURCE_EXHAUSTED")
                    .message("rate limited")
                    .details(List.of(new RetryInfoDetail(delay)))
                    .build();

            assertThat(entry.getRetryDelay()).isEqualTo(delay);
        }

        @Test
        @DisplayName("RetryInfoDetail with null duration -> null")
        void withNullDuration_returnsNull() {
            ErrorEntry entry = ErrorEntry.builder()
                    .httpStatus(HttpStatus.SERVICE_UNAVAILABLE)
                    .errorCode("UNAVAILABLE")
                    .message("unavailable")
                    .details(List.of(new RetryInfoDetail()))
                    .build();

            assertThat(entry.getRetryDelay()).isNull();
        }

        @Test
        @DisplayName("no RetryInfoDetail -> null")
        void noRetryInfo_returnsNull() {
            ErrorEntry entry = ErrorEntry.builder()
                    .httpStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                    .errorCode("INTERNAL")
                    .message("error")
                    .details(List.of(ErrorInfoDetail.builder().build()))
                    .build();

            assertThat(entry.getRetryDelay()).isNull();
        }

        @Test
        @DisplayName("no details at all -> null")
        void emptyDetails_returnsNull() {
            ErrorEntry entry = entryWith(null);

            assertThat(entry.getRetryDelay()).isNull();
        }
    }

    private static ErrorEntry entryWith(List<? extends space.br1440.platform.errorhandling.model.error_detail.ErrorDetail> details) {
        return ErrorEntry.builder()
                .httpStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                .errorCode("INTERNAL")
                .message("error")
                .details(details != null ? details : List.of())
                .build();
    }
}
