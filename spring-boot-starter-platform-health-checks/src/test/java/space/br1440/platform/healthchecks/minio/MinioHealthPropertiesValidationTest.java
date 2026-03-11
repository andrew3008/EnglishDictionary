package space.br1440.platform.healthchecks.minio;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MinioHealthProperties: validation")
class MinioHealthPropertiesValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Nested
    @DisplayName("root properties")
    class RootProperties {

        @Test
        @DisplayName("дефолтные настройки проходят валидацию")
        void defaultPropertiesAreValid() {
            // Given
            MinioHealthProperties props = new MinioHealthProperties();

            // When / Then
            assertNoViolations(props);
        }

        @Test
        @DisplayName("probes=null → violation")
        void nullSeveritiesIsInvalid() {
            // Given
            MinioHealthProperties props = new MinioHealthProperties();
            props.setProbes(null);

            // When / Then
            assertViolationOn(props, "probes");
        }
    }

    @Nested
    @DisplayName("Async")
    class AsyncValidation {

        @Test
        @DisplayName("дефолтные настройки валидны")
        void defaultAsyncIsValid() {
            // Given
            MinioHealthProperties.Async async = new MinioHealthProperties.Async();

            // When / Then
            assertNoViolations(async);
        }

        @ParameterizedTest(name = "healthCheckTimeout={0} → violation (@PositiveDuration)")
        @DisplayName("healthCheckTimeout ≤ 0 → violation")
        @MethodSource("space.br1440.platform.healthchecks.minio.MinioHealthPropertiesValidationTest#nonPositiveDurations")
        void nonPositiveHealthCheckTimeoutIsInvalid(Duration value) {
            // Given
            MinioHealthProperties.Async async = new MinioHealthProperties.Async();
            async.setHealthCheckTimeout(value);

            // When / Then
            assertViolationOn(async, "healthCheckTimeout");
        }

        @ParameterizedTest(name = "refreshInterval={0} → violation (@PositiveDuration)")
        @DisplayName("refreshInterval ≤ 0 → violation")
        @MethodSource("space.br1440.platform.healthchecks.minio.MinioHealthPropertiesValidationTest#nonPositiveDurations")
        void nonPositiveRefreshIntervalIsInvalid(Duration value) {
            // Given
            MinioHealthProperties.Async async = new MinioHealthProperties.Async();
            async.setRefreshInterval(value);

            // When / Then
            assertViolationOn(async, "refreshInterval");
        }

        @ParameterizedTest(name = "staleResultTtl={0} → violation (@PositiveDuration)")
        @DisplayName("staleResultTtl ≤ 0 → violation")
        @MethodSource("space.br1440.platform.healthchecks.minio.MinioHealthPropertiesValidationTest#nonPositiveDurations")
        void nonPositiveStaleResultTtlIsInvalid(Duration value) {
            // Given
            MinioHealthProperties.Async async = new MinioHealthProperties.Async();
            async.setStaleResultTtl(value);

            // When / Then
            assertViolationOn(async, "staleResultTtl");
        }

        @Test
        @DisplayName("healthCheckTimeout >= staleResultTtl → @ValidAsyncTimeout violation")
        void checkTimeoutGreaterThanOrEqualToStaleTtlIsInvalid() {
            // Given — healthCheckTimeout == staleResultTtl
            MinioHealthProperties.Async async = new MinioHealthProperties.Async();
            async.setHealthCheckTimeout(Duration.ofSeconds(30));
            async.setStaleResultTtl(Duration.ofSeconds(30));

            // When
            Set<ConstraintViolation<MinioHealthProperties.Async>> violations = validate(async);

            // Then
            assertThat(violations).isNotEmpty();
        }

        @Test
        @DisplayName("healthCheckTimeout < staleResultTtl → @ValidAsyncTimeout valid")
        void checkTimeoutLessThanStaleTtlIsValid() {
            // Given
            MinioHealthProperties.Async async = new MinioHealthProperties.Async();
            async.setHealthCheckTimeout(Duration.ofSeconds(10));
            async.setStaleResultTtl(Duration.ofSeconds(120));

            // When / Then
            assertNoViolations(async);
        }

        @Test
        @DisplayName("ISO-8601 эквивалентен short-format: PT15S == 15s")
        void iso8601AndShortFormatAreEquivalent() {
            // Given — оба представления одного значения проходят валидацию
            MinioHealthProperties.Async async = new MinioHealthProperties.Async();
            async.setHealthCheckTimeout(Duration.ofSeconds(15));  // 15s / PT15S

            // When / Then
            assertNoViolations(async);
        }
    }

    @Nested
    @DisplayName("Timeout")
    class TimeoutValidation {

        @Test
        @DisplayName("дефолтные настройки валидны")
        void defaultTimeoutIsValid() {
            // Given
            MinioHealthProperties.Timeout timeout = new MinioHealthProperties.Timeout();

            // When / Then
            assertNoViolations(timeout);
        }

        @Test
        @DisplayName("connectTimeout=null → violation")
        void nullConnectTimeoutIsInvalid() {
            // Given
            MinioHealthProperties.Timeout timeout = new MinioHealthProperties.Timeout();
            timeout.setConnectTimeout(null);

            // When / Then
            assertViolationOn(timeout, "connectTimeout");
        }

        @Test
        @DisplayName("healthCheckTimeout=null → violation (@NotNull)")
        void nullResponseTimeoutIsInvalid() {
            // Given
            MinioHealthProperties.Timeout timeout = new MinioHealthProperties.Timeout();
            timeout.setHealthCheckTimeout(null);

            // When / Then
            assertViolationOn(timeout, "healthCheckTimeout");
        }

        @Test
        @DisplayName("connectTimeout=zero → violation (@PositiveDuration)")
        void zeroConnectTimeoutIsInvalid() {
            // Given
            MinioHealthProperties.Timeout timeout = new MinioHealthProperties.Timeout();
            timeout.setConnectTimeout(Duration.ZERO);

            // When / Then
            assertViolationOn(timeout, "connectTimeout");
        }

        @Test
        @DisplayName("healthCheckTimeout=negative → violation (@PositiveDuration)")
        void negativeResponseTimeoutIsInvalid() {
            // Given
            MinioHealthProperties.Timeout timeout = new MinioHealthProperties.Timeout();
            timeout.setHealthCheckTimeout(Duration.ofSeconds(-1));

            // When / Then
            assertViolationOn(timeout, "healthCheckTimeout");
        }
    }

    @Nested
    @DisplayName("Buckets")
    class BucketsValidation {

        @Test
        @DisplayName("дефолтные настройки валидны (probeBucket=null, monitoredBuckets=null)")
        void defaultBucketsIsValid() {
            // Given
            MinioHealthProperties.Buckets buckets = new MinioHealthProperties.Buckets();

            // When / Then
            assertNoViolations(buckets);
        }

        @Test
        @DisplayName("корректный probeBucket валиден")
        void validProbeBucketIsValid() {
            // Given
            MinioHealthProperties.Buckets buckets = new MinioHealthProperties.Buckets();
            buckets.setProbeBucket("valid-bucket-name");

            // When / Then
            assertNoViolations(buckets);
        }

        @Test
        @DisplayName("невалидный probeBucket → violation (@ValidBucketName)")
        void invalidProbeBucketIsInvalid() {
            // Given
            MinioHealthProperties.Buckets buckets = new MinioHealthProperties.Buckets();
            buckets.setProbeBucket("Invalid_Bucket!");

            // When / Then
            assertViolationOn(buckets, "probeBucket");
        }

        @Test
        @DisplayName("monitoredBuckets с 100 элементами валиден (граничное значение)")
        void hundredMonitoredBucketsIsValid() {
            // Given
            MinioHealthProperties.Buckets buckets = new MinioHealthProperties.Buckets();
            buckets.setMonitoredBuckets(validBucketNames(100));

            // When / Then
            assertNoViolations(buckets);
        }

        @Test
        @DisplayName("monitoredBuckets с 101 элементом → violation (@Size max=100)")
        void tooManyMonitoredBucketsIsInvalid() {
            // Given
            MinioHealthProperties.Buckets buckets = new MinioHealthProperties.Buckets();
            buckets.setMonitoredBuckets(validBucketNames(101));

            // When / Then
            assertViolationOn(buckets, "monitoredBuckets");
        }

        @Test
        @DisplayName("невалидное имя в monitoredBuckets → violation")
        void invalidBucketNameInListIsInvalid() {
            // Given
            MinioHealthProperties.Buckets buckets = new MinioHealthProperties.Buckets();
            buckets.setMonitoredBuckets(List.of("valid-bucket", "INVALID_NAME!"));

            // When
            Set<ConstraintViolation<MinioHealthProperties.Buckets>> violations = validate(buckets);

            // Then
            assertThat(violations).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("SentinelCheck")
    class SentinelCheckValidation {

        @Test
        @DisplayName("дефолтные настройки валидны (enabled=false)")
        void defaultSentinelCheckIsValid() {
            // Given
            MinioHealthProperties.SentinelCheck check = new MinioHealthProperties.SentinelCheck();

            // When / Then
            assertNoViolations(check);
        }

        @Test
        @DisplayName("enabled=true с корректным bucket и objectKey валиден")
        void enabledWithValidConfigIsValid() {
            // Given
            MinioHealthProperties.SentinelCheck check = new MinioHealthProperties.SentinelCheck();
            check.setEnabled(true);
            check.setSentinelBucket("sentinel-bucket");
            check.setSentinelObjectKey(".health-sentinel");

            // When / Then
            assertNoViolations(check);
        }

        @Test
        @DisplayName("enabled=true, sentinelBucket=null → violation (@ValidSentinelCheck)")
        void enabledWithNullBucketIsInvalid() {
            // Given
            MinioHealthProperties.SentinelCheck check = new MinioHealthProperties.SentinelCheck();
            check.setEnabled(true);
            check.setSentinelBucket(null);

            // When
            Set<ConstraintViolation<MinioHealthProperties.SentinelCheck>> violations = validate(check);

            // Then
            assertThat(violations).isNotEmpty();
        }

        @Test
        @DisplayName("enabled=false, sentinelBucket=null → valid (отключённая проверка не валидируется)")
        void disabledWithNullBucketIsValid() {
            // Given
            MinioHealthProperties.SentinelCheck check = new MinioHealthProperties.SentinelCheck();
            check.setEnabled(false);
            check.setSentinelBucket(null);

            // When / Then
            assertNoViolations(check);
        }

        @Test
        @DisplayName("sentinelObjectKey=blank → violation (@NotBlank)")
        void blankObjectKeyIsInvalid() {
            // Given
            MinioHealthProperties.SentinelCheck check = new MinioHealthProperties.SentinelCheck();
            check.setEnabled(true);
            check.setSentinelBucket("sentinel-bucket");
            check.setSentinelObjectKey("   ");

            // When / Then
            assertViolationOn(check, "sentinelObjectKey");
        }

        @Test
        @DisplayName("невалидный sentinelBucket → violation (@ValidBucketName)")
        void invalidSentinelBucketNameIsInvalid() {
            // Given
            MinioHealthProperties.SentinelCheck check = new MinioHealthProperties.SentinelCheck();
            check.setEnabled(true);
            check.setSentinelBucket("INVALID_BUCKET!");

            // When
            Set<ConstraintViolation<MinioHealthProperties.SentinelCheck>> violations = validate(check);

            // Then
            assertThat(violations).isNotEmpty();
        }
    }

    static Stream<Duration> nonPositiveDurations() {
        return Stream.of(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofSeconds(-100));
    }

    private <T> Set<ConstraintViolation<T>> validate(T object) {
        return validator.validate(object);
    }

    private <T> void assertNoViolations(T object) {
        assertThat(validate(object)).isEmpty();
    }

    private <T> void assertViolationOn(T object, String propertyPath) {
        Set<ConstraintViolation<T>> violations = validate(object);
        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .contains(propertyPath);
    }

    private List<String> validBucketNames(int count) {
        return IntStream.rangeClosed(1, count)
                .mapToObj("bucket-%d"::formatted)
                .toList();
    }
}
