package space.br1440.platform.healthchecks.postgres;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PostgresHealthProperties: validation")
class PostgresHealthPropertiesValidationTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidatorFactory() {
        if (validatorFactory != null) {
            validatorFactory.close();
        }
    }

    @Nested
    @DisplayName("root properties")
    class RootProperties {

        @Test
        @DisplayName("дефолтный объект → 0 violations")
        void defaultPropertiesAreValid() {
            // Given
            PostgresHealthProperties props = new PostgresHealthProperties();

            // When / Then
            assertNoViolations(props);
        }

        @Test
        @DisplayName("probes=null → violation на 'probes'")
        void nullSeveritiesIsInvalid() {
            // Given
            PostgresHealthProperties props = new PostgresHealthProperties();
            props.setProbes(null);

            // When / Then
            assertViolationOn(props, "probes");
        }

        @Test
        @DisplayName("healthCheckTimeout=null → violation на 'healthCheckTimeout' (@NotNull)")
        void nullQueryTimeoutIsInvalid() {
            // Given
            PostgresHealthProperties props = new PostgresHealthProperties();
            props.setHealthCheckTimeout(null);

            // When / Then
            assertViolationOn(props, "healthCheckTimeout");
        }

        @Test
        @DisplayName("healthCheckTimeout=Duration.ZERO → violation на 'healthCheckTimeout' (@PositiveDuration)")
        void zeroDurationQueryTimeoutIsInvalid() {
            // Given
            PostgresHealthProperties props = new PostgresHealthProperties();
            props.setHealthCheckTimeout(Duration.ZERO);

            // When / Then
            assertViolationOn(props, "healthCheckTimeout");
        }

        @Test
        @DisplayName("healthCheckTimeout=Duration.ofSeconds(-1) → violation на 'healthCheckTimeout' (@PositiveDuration)")
        void negativeDurationQueryTimeoutIsInvalid() {
            // Given
            PostgresHealthProperties props = new PostgresHealthProperties();
            props.setHealthCheckTimeout(Duration.ofSeconds(-1));

            // When / Then
            assertViolationOn(props, "healthCheckTimeout");
        }

        @Test
        @DisplayName("queryTimeout=Duration.ofMillis(1) → valid (минимальное позитивное значение)")
        void minimalPositiveDurationQueryTimeoutIsValid() {
            // Given
            PostgresHealthProperties props = new PostgresHealthProperties();
            props.setHealthCheckTimeout(Duration.ofMillis(1));

            // When / Then
            assertNoViolations(props);
        }
    }

    @Nested
    @DisplayName("ConnectionPool — прямая валидация")
    class ConnectionPoolValidation {

        @Test
        @DisplayName("дефолтный ConnectionPool объект → 0 violations")
        void defaultConnectionPoolIsValid() {
            // Given
            PostgresHealthProperties.ConnectionPool pool = new PostgresHealthProperties.ConnectionPool();

            // When / Then
            assertNoViolations(pool);
        }

        @Test
        @DisplayName("minAvailablePercent=-1 → violation на 'minAvailablePercent' (@Min(0))")
        void negativeMinAvailablePercentIsInvalid() {
            // Given
            PostgresHealthProperties.ConnectionPool pool = new PostgresHealthProperties.ConnectionPool();
            pool.setMinAvailablePercent(-1);

            // When / Then
            assertViolationOn(pool, "minAvailablePercent");
        }

        @Test
        @DisplayName("minAvailablePercent=101 → violation на 'minAvailablePercent' (@Max(100))")
        void minAvailablePercentAbove100IsInvalid() {
            // Given
            PostgresHealthProperties.ConnectionPool pool = new PostgresHealthProperties.ConnectionPool();
            pool.setMinAvailablePercent(101);

            // When / Then
            assertViolationOn(pool, "minAvailablePercent");
        }

        @ParameterizedTest(name = "minAvailablePercent={0} → valid")
        @DisplayName("minAvailablePercent на границах 0 и 100 → valid")
        @ValueSource(ints = {0, 100})
        void minAvailablePercentAtBoundariesIsValid(int value) {
            // Given
            PostgresHealthProperties.ConnectionPool pool = new PostgresHealthProperties.ConnectionPool();
            pool.setMinAvailablePercent(value);

            // When / Then
            assertNoViolations(pool);
        }

        @Test
        @DisplayName("maxWaitingThreads=-1 → violation на 'maxWaitingThreads' (@PositiveOrZero)")
        void negativeMaxWaitingThreadsIsInvalid() {
            // Given
            PostgresHealthProperties.ConnectionPool pool = new PostgresHealthProperties.ConnectionPool();
            pool.setMaxWaitingThreads(-1);

            // When / Then
            assertViolationOn(pool, "maxWaitingThreads");
        }

        @Test
        @DisplayName("maxWaitingThreads=0 → valid (граница @PositiveOrZero)")
        void zeroMaxWaitingThreadsIsValid() {
            // Given
            PostgresHealthProperties.ConnectionPool pool = new PostgresHealthProperties.ConnectionPool();
            pool.setMaxWaitingThreads(0);

            // When / Then
            assertNoViolations(pool);
        }
    }

    @Nested
    @DisplayName("каскадная валидация ConnectionPool через @Valid на корневом объекте")
    class CascadedConnectionPoolValidation {

        @Test
        @DisplayName("minAvailablePercent=-1 на вложенном объекте → violation с путём 'connectionPool.minAvailablePercent'")
        void cascadedViolationWhenMinAvailablePercentInvalid() {
            // Given
            PostgresHealthProperties props = new PostgresHealthProperties();
            props.getConnectionPool().setMinAvailablePercent(-1);

            // When
            Set<ConstraintViolation<PostgresHealthProperties>> violations = validate(props);

            // Then
            assertThat(violations)
                    .extracting(v -> v.getPropertyPath().toString())
                    .contains("connectionPool.minAvailablePercent");
        }

        @Test
        @DisplayName("maxWaitingThreads=-1 на вложенном объекте → violation с путём 'connectionPool.maxWaitingThreads'")
        void cascadedViolationWhenMaxWaitingThreadsInvalid() {
            // Given
            PostgresHealthProperties props = new PostgresHealthProperties();
            props.getConnectionPool().setMaxWaitingThreads(-1);

            // When
            Set<ConstraintViolation<PostgresHealthProperties>> violations = validate(props);

            // Then
            assertThat(violations)
                    .extracting(v -> v.getPropertyPath().toString())
                    .contains("connectionPool.maxWaitingThreads");
        }

        @Test
        @DisplayName("оба violations — из root и вложенного — выбрасываются одновременно")
        void rootAndCascadedViolationsReportedTogether() {
            // Given — нарушение в root (healthCheckTimeout=null) И во вложенном (minAvailablePercent=-1)
            PostgresHealthProperties props = new PostgresHealthProperties();
            props.setHealthCheckTimeout(null);
            props.getConnectionPool().setMinAvailablePercent(-1);

            // When
            Set<ConstraintViolation<PostgresHealthProperties>> violations = validate(props);

            // Then — оба поля в одном прогоне валидации
            assertThat(violations)
                    .extracting(v -> v.getPropertyPath().toString())
                    .contains("healthCheckTimeout", "connectionPool.minAvailablePercent");
        }
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
                .as("Expected violation on property '%s'", propertyPath)
                .extracting(v -> v.getPropertyPath().toString())
                .contains(propertyPath);
    }
}
