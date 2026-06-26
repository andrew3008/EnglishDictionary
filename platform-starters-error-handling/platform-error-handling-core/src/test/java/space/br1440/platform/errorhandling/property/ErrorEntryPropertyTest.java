package space.br1440.platform.errorhandling.property;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.error_detail.ErrorDetail;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;
import space.br1440.platform.errorhandling.model.error_detail.RetryInfoDetail;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Инварианты {@link ErrorEntry}: детерминированная генерация полей (JUnit&nbsp;5 + циклы, без jqwik).
 */
@DisplayName("ErrorEntry (инварианты)")
class ErrorEntryPropertyTest {

    private static final int ITERATIONS = 400;

    private static final HttpStatus[] HTTP_STATUSES = {
            HttpStatus.OK,
            HttpStatus.BAD_REQUEST,
            HttpStatus.NOT_FOUND,
            HttpStatus.INTERNAL_SERVER_ERROR,
            HttpStatus.SERVICE_UNAVAILABLE,
    };

    private static final String[] ERROR_CODES = {"ERR-001", "ERR-002", "DOMAIN-100", "SVC-500"};

    private static final String[] MESSAGES = {
            "Something went wrong",
            "Resource not found",
            "Invalid request",
            "Service unavailable",
    };

    private static final String[] SAMPLE_KEYS = {"key1", "key2", "key3"};
    private static final String[] SAMPLE_VALS = {"val1", "val2", "val3"};

    private static final long SEED_RETRYABLE = 0xE877_3E01_4010_DEF1L;
    private static final long SEED_RETRY_DELAY = 0xE877_3E02_4020_DEF2L;
    private static final long SEED_UNMODIFIABLE = 0xE877_3E03_4030_DEF3L;
    private static final long SEED_NO_RETRY = 0xE877_3E04_4040_DEF4L;

    @Test
    @DisplayName("isRetryable() ⟺ в details есть хотя бы один RetryInfoDetail")
    void isRetryable_iffDetailsContainsRetryInfoDetail() {
        SplittableRandom rng = new SplittableRandom(SEED_RETRYABLE);
        for (int i = 0; i < ITERATIONS; i++) {
            ErrorEntry entry = randomErrorEntry(rng);
            boolean hasRetryInfo = entry.getDetails().stream()
                    .anyMatch(RetryInfoDetail.class::isInstance);
            assertThat(entry.isRetryable()).isEqualTo(hasRetryInfo);
        }
    }

    @Test
    @DisplayName("getRetryDelay() — задержка первого RetryInfoDetail или null")
    void getRetryDelay_returnsFirstRetryInfoDelayOrNull() {
        SplittableRandom rng = new SplittableRandom(SEED_RETRY_DELAY);
        for (int i = 0; i < ITERATIONS; i++) {
            ErrorEntry entry = randomErrorEntry(rng);
            Duration expectedDelay = entry.getDetails().stream()
                    .filter(RetryInfoDetail.class::isInstance)
                    .map(RetryInfoDetail.class::cast)
                    .findFirst()
                    .map(RetryInfoDetail::getRetryDelay)
                    .orElse(null);
            assertThat(entry.getRetryDelay()).isEqualTo(expectedDelay);
        }
    }

    @Test
    @DisplayName("builder без details — non-null пустой список")
    void builder_alwaysCreatesNonNullDetailsList() {
        for (HttpStatus status : HTTP_STATUSES) {
            for (String code : ERROR_CODES) {
                for (String msg : MESSAGES) {
                    ErrorEntry entry = ErrorEntry.builder()
                            .httpStatus(status)
                            .errorCode(code)
                            .message(msg)
                            .build();
                    assertThat(entry.getDetails()).isNotNull();
                    assertThat(entry.getDetails()).isEmpty();
                }
            }
        }
    }

    @Test
    @DisplayName("getDetails() — неизменяемый список")
    void getDetails_returnsUnmodifiableList() {
        SplittableRandom rng = new SplittableRandom(SEED_UNMODIFIABLE);
        for (int i = 0; i < ITERATIONS; i++) {
            ErrorEntry entry = randomErrorEntry(rng);
            List<ErrorDetail> details = entry.getDetails();
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> details.add(new RetryInfoDetail()));
        }
    }

    @Test
    @DisplayName("Без RetryInfoDetail: не retryable и getRetryDelay == null")
    void noRetryInfo_meansNotRetryableAndNullDelay() {
        SplittableRandom rng = new SplittableRandom(SEED_NO_RETRY);
        for (int i = 0; i < ITERATIONS; i++) {
            HttpStatus status = HTTP_STATUSES[rng.nextInt(HTTP_STATUSES.length)];
            String code = ERROR_CODES[rng.nextInt(ERROR_CODES.length)];
            String msg = MESSAGES[rng.nextInt(MESSAGES.length)];
            List<ErrorDetail> details = randomDetailListWithoutRetry(rng);

            ErrorEntry entry = ErrorEntry.builder()
                    .httpStatus(status)
                    .errorCode(code)
                    .message(msg)
                    .details(details)
                    .build();

            assertThat(entry.isRetryable()).isFalse();
            assertThat(entry.getRetryDelay()).isNull();
        }
    }

    private static ErrorEntry randomErrorEntry(SplittableRandom rng) {
        HttpStatus status = HTTP_STATUSES[rng.nextInt(HTTP_STATUSES.length)];
        String code = ERROR_CODES[rng.nextInt(ERROR_CODES.length)];
        String msg = MESSAGES[rng.nextInt(MESSAGES.length)];
        List<ErrorDetail> details = randomDetailListAllowingRetry(rng);
        return ErrorEntry.builder()
                .httpStatus(status)
                .errorCode(code)
                .message(msg)
                .details(details)
                .build();
    }

    private static List<ErrorDetail> randomDetailListAllowingRetry(SplittableRandom rng) {
        int size = rng.nextInt(0, 6);
        List<ErrorDetail> details = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            if (rng.nextBoolean()) {
                if (rng.nextBoolean()) {
                    details.add(new RetryInfoDetail(Duration.ofSeconds(rng.nextLong(0, 301))));
                } else {
                    details.add(new RetryInfoDetail());
                }
            } else {
                details.add(randomErrorInfoDetail(rng));
            }
        }
        return details;
    }

    private static List<ErrorDetail> randomDetailListWithoutRetry(SplittableRandom rng) {
        int size = rng.nextInt(0, 6);
        List<ErrorDetail> details = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            details.add(randomErrorInfoDetail(rng));
        }
        return details;
    }

    private static ErrorInfoDetail randomErrorInfoDetail(SplittableRandom rng) {
        int mapSize = rng.nextInt(0, 4);
        Map<String, String> meta = new LinkedHashMap<>();
        for (int i = 0; i < mapSize; i++) {
            meta.put(SAMPLE_KEYS[rng.nextInt(SAMPLE_KEYS.length)], SAMPLE_VALS[rng.nextInt(SAMPLE_VALS.length)]);
        }
        return ErrorInfoDetail.builder()
                .clientMetadata(meta)
                .diagnosticMetadata(meta)
                .build();
    }
}
