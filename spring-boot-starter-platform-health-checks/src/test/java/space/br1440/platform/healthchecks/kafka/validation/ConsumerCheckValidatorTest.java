package space.br1440.platform.healthchecks.kafka.validation;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import space.br1440.platform.healthchecks.kafka.KafkaHealthProperties;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ConsumerCheckValidator (@ValidConsumerCheck)")
class ConsumerCheckValidatorTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    @Nested
    @DisplayName("consumer.enabled=false — constraint пропускается")
    class ConsumerDisabled {

        @Test
        @DisplayName("валидация проходит когда enabled=false и groupId=null")
        void shouldPassWhenDisabledWithNullGroupId() {
            // Given
            KafkaHealthProperties.Consumer consumer = consumer(false, null);

            // When
            Set<ConstraintViolation<KafkaHealthProperties.Consumer>> violations = validate(consumer);

            // Then
            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("валидация проходит когда enabled=false и groupId пустой")
        void shouldPassWhenDisabledWithBlankGroupId() {
            // Given
            KafkaHealthProperties.Consumer consumer = consumer(false, "   ");

            // When
            Set<ConstraintViolation<KafkaHealthProperties.Consumer>> violations = validate(consumer);

            // Then
            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("валидация проходит когда enabled=false (значение по умолчанию) и groupId не задан")
        void shouldPassForDefaultState() {
            // Given
            KafkaHealthProperties.Consumer consumer = new KafkaHealthProperties.Consumer();

            // When
            Set<ConstraintViolation<KafkaHealthProperties.Consumer>> violations = validate(consumer);

            // Then
            assertThat(violations).isEmpty();
        }
    }

    @Nested
    @DisplayName("consumer.enabled=true, groupId валиден — constraint проходит")
    class ConsumerEnabledValid {

        @Test
        @DisplayName("валидация проходит когда enabled=true и groupId задан")
        void shouldPassWhenEnabledWithValidGroupId() {
            // Given
            KafkaHealthProperties.Consumer consumer = consumer(true, "payments-consumer-group");

            // When
            Set<ConstraintViolation<KafkaHealthProperties.Consumer>> violations = validate(consumer);

            // Then
            assertThat(violations).isEmpty();
        }

        @ParameterizedTest(name = "groupId=\"{0}\"")
        @ValueSource(strings = {"g", "my-group", "payments.consumer.group", "group_v2", "GROUP-123"})
        @DisplayName("валидация проходит для различных допустимых форматов groupId")
        void shouldPassForVariousValidGroupIdFormats(String groupId) {
            // Given
            KafkaHealthProperties.Consumer consumer = consumer(true, groupId);

            // When
            Set<ConstraintViolation<KafkaHealthProperties.Consumer>> violations = validate(consumer);

            // Then
            assertThat(violations).isEmpty();
        }
    }

    @Nested
    @DisplayName("consumer.enabled=true, groupId невалиден — constraint нарушается")
    class ConsumerEnabledInvalid {

        @Test
        @DisplayName("нарушение constraint когда enabled=true и groupId=null")
        void shouldViolateWhenEnabledWithNullGroupId() {
            // Given
            KafkaHealthProperties.Consumer consumer = consumer(true, null);

            // When
            Set<ConstraintViolation<KafkaHealthProperties.Consumer>> violations = validate(consumer);

            // Then
            assertThat(violations).isNotEmpty();
        }

        @ParameterizedTest(name = "groupId=\"{0}\"")
        @NullAndEmptySource
        @ValueSource(strings = {" ", "   ", "\t", "\n"})
        @DisplayName("нарушение constraint когда enabled=true и groupId пустой или blank")
        void shouldViolateWhenEnabledWithBlankOrNullGroupId(String groupId) {
            // Given
            KafkaHealthProperties.Consumer consumer = consumer(true, groupId);

            // When
            Set<ConstraintViolation<KafkaHealthProperties.Consumer>> violations = validate(consumer);

            // Then
            assertThat(violations).isNotEmpty();
        }

        @Test
        @DisplayName("сообщение violation содержит текст из @ValidConsumerCheck.message()")
        void shouldUseAnnotationDefaultMessage() {
            // Given
            KafkaHealthProperties.Consumer consumer = consumer(true, null);

            // When
            Set<ConstraintViolation<KafkaHealthProperties.Consumer>> violations = validate(consumer);

            // Then
            assertThat(violations)
                    .extracting(ConstraintViolation::getMessage)
                    .contains("Consumer group ID must be specified when consumer check is enabled");
        }

        @Test
        @DisplayName("violation аннотирован @ValidConsumerCheck (не @NotBlank)")
        void shouldBeAnnotatedByValidConsumerCheckNotNotBlank() {
            // Given
            KafkaHealthProperties.Consumer consumer = consumer(true, null);

            // When
            Set<ConstraintViolation<KafkaHealthProperties.Consumer>> violations = validate(consumer);

            // Then — constraint должен быть именно @ValidConsumerCheck, а не @NotBlank на поле
            assertThat(violations)
                    .extracting(v -> v.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName())
                    .contains("ValidConsumerCheck");
        }
    }

    @Nested
    @DisplayName("null value — безопасность против NPE")
    class NullSafety {

        @Test
        @DisplayName("isValid возвращает true когда value=null (null-safe guard)")
        void shouldReturnTrueWhenValueIsNull() {
            // Given
            ConsumerCheckValidator consumerValidator = new ConsumerCheckValidator();

            // When
            boolean result = consumerValidator.isValid(null, null);

            // Then
            assertThat(result).isTrue();
        }
    }

    @Nested
    @DisplayName("интеграция с KafkaHealthProperties (constraint на классе Consumer)")
    class IntegrationWithProperties {

        @Test
        @DisplayName("нет violations для KafkaHealthProperties с consumer.enabled=false по умолчанию")
        void shouldHaveNoViolationsForDefaultProperties() {
            // Given
            KafkaHealthProperties properties = new KafkaHealthProperties();

            // When
            Set<ConstraintViolation<KafkaHealthProperties>> violations = validator.validate(properties);

            // Then
            Set<ConstraintViolation<KafkaHealthProperties>> consumerViolations = violations.stream()
                    .filter(v -> v.getPropertyPath().toString().startsWith("consumer"))
                    .collect(java.util.stream.Collectors.toSet());
            assertThat(consumerViolations).isEmpty();
        }

        @Test
        @DisplayName("violation на consumer когда enabled=true и groupId не задан в KafkaHealthProperties")
        void shouldReportViolationOnPropertiesWhenConsumerEnabledWithoutGroupId() {
            // Given
            KafkaHealthProperties properties = new KafkaHealthProperties();
            properties.getConsumer().setEnabled(true);

            // When
            Set<ConstraintViolation<KafkaHealthProperties>> violations = validator.validate(properties);

            // Then
            assertThat(violations)
                    .extracting(v -> v.getPropertyPath().toString())
                    .anyMatch(path -> path.contains("consumer"));
        }
    }

    private Set<ConstraintViolation<KafkaHealthProperties.Consumer>> validate(KafkaHealthProperties.Consumer consumer) {
        return validator.validate(consumer);
    }

    private static KafkaHealthProperties.Consumer consumer(boolean enabled, String groupId) {
        return new KafkaHealthProperties.Consumer()
                .setEnabled(enabled)
                .setGroupId(groupId);
    }
}
