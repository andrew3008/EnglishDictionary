package space.br1440.platform.healthchecks.minio.validation;

import jakarta.validation.ConstraintValidatorContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("BucketNameValidator: bucket name validation rules")
class BucketNameValidatorTest {

    private final BucketNameValidator validator = new BucketNameValidator();
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

    @ParameterizedTest(name = "''{0}'' → valid")
    @DisplayName("корректные имена бакетов")
    @ValueSource(strings = {
            "my-bucket",
            "my.bucket",
            "my-bucket-123",
            "abc", // минимальная длина — 3 символа
            "a1b",
            "bucket-with-hyphen",
            "bucket.with.dots",
            "123bucket",
            "bucket123",
            "a23456789012345678901234567890123456789012345678901234567890123", // 63 символа
    })
    void validBucketNames(String name) {
        assertThat(validator.isValid(name, context)).isTrue();
    }

    @Test
    @DisplayName("пустая строка → invalid")
    void emptyStringIsInvalid() {
        assertThat(validator.isValid("", context)).isFalse();
    }

    @Test
    @DisplayName("1 символ → invalid (меньше минимума 3)")
    void oneCharIsInvalid() {
        assertThat(validator.isValid("a", context)).isFalse();
    }

    @Test
    @DisplayName("2 символа → invalid (минимум 3)")
    void twoCharsIsInvalid() {
        assertThat(validator.isValid("ab", context)).isFalse();
    }

    @Test
    @DisplayName("3 символа → valid (минимальная допустимая длина)")
    void threeCharsIsValid() {
        assertThat(validator.isValid("abc", context)).isTrue();
    }

    @Test
    @DisplayName("63 символа → valid (максимальная допустимая длина)")
    void maxLengthIsValid() {
        String name = "a" + "b".repeat(61) + "c"; // 63 символа
        assertThat(validator.isValid(name, context)).isTrue();
    }

    @Test
    @DisplayName("64 символа → invalid (превышает максимум)")
    void tooLongIsInvalid() {
        String name = "a" + "b".repeat(62) + "c"; // 64 символа
        assertThat(validator.isValid(name, context)).isFalse();
    }

    @ParameterizedTest(name = "''{0}'' → invalid")
    @DisplayName("недопустимые символы в имени бакета")
    @ValueSource(strings = {
            "Bucket",    // заглавные буквы
            "my_bucket", // underscore
            "my bucket", // пробел
            "my@bucket", // @
            "my/bucket", // слэш
            "my+bucket", // плюс
    })
    void invalidCharactersAreRejected(String name) {
        assertThat(validator.isValid(name, context)).isFalse();
    }

    @ParameterizedTest(name = "''{0}'' → invalid")
    @DisplayName("имя начинается или заканчивается на дефис/точку")
    @ValueSource(strings = {
            "-bucket",
            "bucket-",
            ".bucket",
            "bucket.",
    })
    void invalidStartOrEnd(String name) {
        assertThat(validator.isValid(name, context)).isFalse();
    }

    @Test
    @DisplayName("двойная точка → invalid")
    void doubleDotIsInvalid() {
        assertThat(validator.isValid("my..bucket", context)).isFalse();
    }

    @Test
    @DisplayName("одиночные точки → valid")
    void singleDotsAreValid() {
        assertThat(validator.isValid("my.bucket.name", context)).isTrue();
    }

    @ParameterizedTest(name = "''{0}'' → invalid (IP-адрес)")
    @DisplayName("IP-адреса запрещены как имена бакетов")
    @ValueSource(strings = {
            "192.168.1.1",
            "10.0.0.1",
            "255.255.255.255",
            "0.0.0.0",
    })
    void ipAddressesAreRejected(String name) {
        assertThat(validator.isValid(name, context)).isFalse();
    }

    @Test
    @DisplayName("строка похожая на IP но с буквами → valid")
    void ipLikeWithLettersIsValid() {
        assertThat(validator.isValid("192.168.1.bucket", context)).isTrue();
    }
}
