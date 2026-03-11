package space.br1440.platform.healthchecks.minio.validation;

import jakarta.validation.ConstraintValidatorContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import space.br1440.platform.healthchecks.minio.MinioHealthProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("SentinelCheckValidator: sentinel check validation rules")
class SentinelCheckValidatorTest {

    private final SentinelCheckValidator validator = new SentinelCheckValidator();
    private ConstraintValidatorContext context;

    @BeforeEach
    void setUp() {
        context = mock(ConstraintValidatorContext.class);
    }

    @Test
    @DisplayName("null → valid (null-проверка — зона ответственности @NotNull)")
    void nullIsValid() {
        assertThat(validator.isValid(null, context)).isTrue();
    }

    @Test
    @DisplayName("enabled=false, bucket=null → valid (отключённая проверка не валидируется)")
    void disabledWithNullBucketIsValid() {
        MinioHealthProperties.SentinelCheck check = disabledCheck();
        check.setSentinelBucket(null);

        assertThat(validator.isValid(check, context)).isTrue();
    }

    @Test
    @DisplayName("enabled=false, bucket=blank → valid (отключённая проверка не валидируется)")
    void disabledWithBlankBucketIsValid() {
        MinioHealthProperties.SentinelCheck check = disabledCheck();
        check.setSentinelBucket("   ");

        assertThat(validator.isValid(check, context)).isTrue();
    }

    @Test
    @DisplayName("enabled=true, bucket задан → valid")
    void enabledWithBucketIsValid() {
        MinioHealthProperties.SentinelCheck check = enabledCheck("my-sentinel-bucket");

        assertThat(validator.isValid(check, context)).isTrue();
    }

    @Test
    @DisplayName("enabled=true, bucket=null → invalid (sentinel bucket обязателен)")
    void enabledWithNullBucketIsInvalid() {
        MinioHealthProperties.SentinelCheck check = enabledCheck(null);

        assertThat(validator.isValid(check, context)).isFalse();
    }

    @ParameterizedTest(name = "bucket=''{0}'' → invalid")
    @DisplayName("enabled=true, bucket пустой или состоит из пробелов → invalid")
    @ValueSource(strings = {"", " ", "   ", "\t", "\n"})
    void enabledWithBlankBucketIsInvalid(String bucket) {
        MinioHealthProperties.SentinelCheck check = enabledCheck(bucket);

        assertThat(validator.isValid(check, context)).isFalse();
    }

    private MinioHealthProperties.SentinelCheck enabledCheck(String bucket) {
        MinioHealthProperties.SentinelCheck check = new MinioHealthProperties.SentinelCheck();
        check.setEnabled(true);
        check.setSentinelBucket(bucket);
        return check;
    }

    private MinioHealthProperties.SentinelCheck disabledCheck() {
        MinioHealthProperties.SentinelCheck check = new MinioHealthProperties.SentinelCheck();
        check.setEnabled(false);
        return check;
    }
}
