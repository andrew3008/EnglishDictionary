package space.br1440.platform.errorhandling.core.strategy.cause.storage;

import org.jooq.exception.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.extension.RootCauseResolution;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;
import space.br1440.platform.errorhandling.model.error_detail.RetryInfoDetail;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("JooqExceptionStrategy")
class JooqExceptionStrategyTest {

    private final JooqExceptionStrategy strategy =
            new JooqExceptionStrategy(new StackHintExtractor(false, List.of()));

    @Nested
    @DisplayName("supportsCause")
    class SupportsCause {

        @Test
        void supports_jooq_DataAccessException() {
            // when / then
            assertThat(strategy.supportsCause(new DataAccessException("sql error"))).isTrue();
        }

        @Test
        void supports_NoDataFoundException() {
            // when / then
            assertThat(strategy.supportsCause(new NoDataFoundException("no data"))).isTrue();
        }

        @Test
        void supports_TooManyRowsException() {
            // when / then
            assertThat(strategy.supportsCause(new TooManyRowsException("too many"))).isTrue();
        }

        @Test
        void supports_IntegrityConstraintViolationException() {
            // when / then
            assertThat(strategy.supportsCause(new IntegrityConstraintViolationException("constraint"))).isTrue();
        }

        @Test
        void supports_DataChangedException() {
            // when / then
            assertThat(strategy.supportsCause(new DataChangedException("changed"))).isTrue();
        }

        @Test
        void supports_MappingException() {
            // when / then
            assertThat(strategy.supportsCause(new MappingException("mapping"))).isTrue();
        }

        @Test
        void supports_DetachedException() {
            // when / then
            assertThat(strategy.supportsCause(new DetachedException("detached"))).isTrue();
        }

        @Test
        void supports_ConfigurationException() {
            // when / then
            assertThat(strategy.supportsCause(new ConfigurationException("config"))).isTrue();
        }

        @Test
        void does_not_support_RuntimeException() {
            // when / then
            assertThat(strategy.supportsCause(new RuntimeException("unrelated"))).isFalse();
        }

        @Test
        void does_not_support_IllegalStateException() {
            // when / then
            assertThat(strategy.supportsCause(new IllegalStateException("illegal"))).isFalse();
        }
    }

    @Nested
    @DisplayName("Result validation exceptions")
    class ResultValidation {

        @Test
        void noDataFound_returns_404() {
            // when
            RootCauseResolution details = strategy.extractCauseDetails(new NoDataFoundException("no rows"));

            // then
            assertThat(details.httpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        void noDataFound_reason_is_NO_DATA_FOUND() {
            // when
            RootCauseResolution details = strategy.extractCauseDetails(new NoDataFoundException("no rows"));

            // then
            assertThat(findInfoDetail(details).getReason()).isEqualTo("NO_DATA_FOUND");
        }

        @Test
        void noDataFound_is_not_retryable() {
            // when
            RootCauseResolution details = strategy.extractCauseDetails(new NoDataFoundException("no rows"));

            // then
            assertThat(details.details()).noneMatch(d -> d instanceof RetryInfoDetail);
        }

        @Test
        void tooManyRows_returns_500() {
            // when
            RootCauseResolution details = strategy.extractCauseDetails(new TooManyRowsException("too many"));

            // then
            assertThat(details.httpStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        void tooManyRows_reason_is_TOO_MANY_ROWS() {
            // when
            RootCauseResolution details = strategy.extractCauseDetails(new TooManyRowsException("too many"));

            // then
            assertThat(findInfoDetail(details).getReason()).isEqualTo("TOO_MANY_ROWS");
        }

        @Test
        void invalidResult_returns_500() {
            // when
            RootCauseResolution details = strategy.extractCauseDetails(new InvalidResultException("invalid"));

            // then
            assertThat(details.httpStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        void invalidResult_reason_is_INVALID_RESULT() {
            // when
            RootCauseResolution details = strategy.extractCauseDetails(new InvalidResultException("invalid"));

            // then
            assertThat(findInfoDetail(details).getReason()).isEqualTo("INVALID_RESULT");
        }

        @Test
        void noDataFound_is_not_absorbed_by_InvalidResultException() {
            // when
            RootCauseResolution details = strategy.extractCauseDetails(new NoDataFoundException("no rows"));

            // then
            assertThat(details.httpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(findInfoDetail(details).getReason()).isEqualTo("NO_DATA_FOUND");
        }

        @Test
        void tooManyRows_is_not_absorbed_by_InvalidResultException() {
            // when
            RootCauseResolution details = strategy.extractCauseDetails(new TooManyRowsException("too many"));

            // then
            assertThat(findInfoDetail(details).getReason()).isEqualTo("TOO_MANY_ROWS");
        }
    }

    @Nested
    @DisplayName("Data integrity exceptions")
    class DataIntegrity {

        @Test
        void integrityViolation_returns_409() {
            // when
            RootCauseResolution details = strategy.extractCauseDetails(
                    new IntegrityConstraintViolationException("constraint violation")
            );

            // then
            assertThat(details.httpStatus()).isEqualTo(HttpStatus.CONFLICT);
        }

        @Test
        void integrityViolation_reason_is_INTEGRITY_VIOLATION() {
            // when
            RootCauseResolution details = strategy.extractCauseDetails(
                    new IntegrityConstraintViolationException("violation")
            );

            // then
            assertThat(findInfoDetail(details).getReason()).isEqualTo("INTEGRITY_VIOLATION");
        }

        @Test
        void integrityViolation_is_not_retryable() {
            // when
            RootCauseResolution details = strategy.extractCauseDetails(
                    new IntegrityConstraintViolationException("violation")
            );

            // then
            assertThat(details.details()).noneMatch(d -> d instanceof RetryInfoDetail);
        }

        @Test
        void dataChanged_returns_409() {
            // when
            RootCauseResolution details = strategy.extractCauseDetails(new DataChangedException("changed"));

            // then
            assertThat(details.httpStatus()).isEqualTo(HttpStatus.CONFLICT);
        }

        @Test
        void dataChanged_is_retryable() {
            // when
            RootCauseResolution details = strategy.extractCauseDetails(new DataChangedException("changed"));

            // then
            assertThat(details.details()).anyMatch(d -> d instanceof RetryInfoDetail);
        }

        @Test
        void dataChanged_reason_is_OPTIMISTIC_LOCK() {
            // when
            RootCauseResolution details = strategy.extractCauseDetails(new DataChangedException("changed"));

            // then
            assertThat(findInfoDetail(details).getReason()).isEqualTo("OPTIMISTIC_LOCK");
        }
    }

    @Nested
    @DisplayName("Type / Mapping exceptions")
    class TypeAndMapping {

        @Test
        void dataTypeException_returns_400() {
            // when
            RootCauseResolution details = strategy.extractCauseDetails(new DataTypeException("type error"));

            // then
            assertThat(details.httpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        void dataTypeException_reason_is_DATA_TYPE_ERROR() {
            // when
            RootCauseResolution details = strategy.extractCauseDetails(new DataTypeException("type error"));

            // then
            assertThat(findInfoDetail(details).getReason()).isEqualTo("DATA_TYPE_ERROR");
        }

        @Test
        void dataException_returns_400() {
            // when
            RootCauseResolution details = strategy.extractCauseDetails(new DataException("data error"));

            // then
            assertThat(details.httpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        void mappingException_returns_500() {
            // when
            RootCauseResolution details = strategy.extractCauseDetails(new MappingException("mapping error"));

            // then
            assertThat(details.httpStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        void mappingException_reason_is_MAPPING_ERROR() {
            // when
            RootCauseResolution details = strategy.extractCauseDetails(new MappingException("mapping error"));

            // then
            assertThat(findInfoDetail(details).getReason()).isEqualTo("MAPPING_ERROR");
        }
    }

    @Nested
    @DisplayName("Configuration exceptions")
    class Configuration {

        @Test
        void detached_returns_500() {
            // when
            RootCauseResolution details = strategy.extractCauseDetails(new DetachedException("detached"));

            // then
            assertThat(details.httpStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        void detached_reason_is_DETACHED() {
            // when
            RootCauseResolution details = strategy.extractCauseDetails(new DetachedException("detached"));

            // then
            assertThat(findInfoDetail(details).getReason()).isEqualTo("DETACHED");
        }

        @Test
        void configurationException_returns_500() {
            // when
            RootCauseResolution details = strategy.extractCauseDetails(
                    new ConfigurationException("config error")
            );

            // then
            assertThat(details.httpStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        void configurationException_reason_is_CONFIGURATION_ERROR() {
            // when
            RootCauseResolution details = strategy.extractCauseDetails(
                    new ConfigurationException("config error")
            );

            // then
            assertThat(findInfoDetail(details).getReason()).isEqualTo("CONFIGURATION_ERROR");
        }

        @Test
        void sqlDialectNotSupported_returns_500() {
            // when
            RootCauseResolution details = strategy.extractCauseDetails(
                    new SQLDialectNotSupportedException("not supported")
            );

            // then
            assertThat(details.httpStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        void loaderConfigException_returns_500() {
            // when
            RootCauseResolution details = strategy.extractCauseDetails(
                    new LoaderConfigurationException("loader error")
            );

            // then
            assertThat(details.httpStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        void templatingException_returns_500() {
            // when
            RootCauseResolution details = strategy.extractCauseDetails(
                    new TemplatingException("template error")
            );

            // then
            assertThat(details.httpStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Nested
    @DisplayName("Infrastructure exceptions")
    class Infrastructure {

        @Test
        void jooqIoException_returns_503() {
            // when
            RootCauseResolution details = strategy.extractCauseDetails(
                    new org.jooq.exception.IOException("io error",
                            new java.io.IOException("connection reset")));

            // then
            assertThat(details.httpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        }

        @Test
        void jooqIoException_is_retryable() {
            // when
            RootCauseResolution details = strategy.extractCauseDetails(
                    new org.jooq.exception.IOException("io error",
                            new java.io.IOException("connection reset")));

            // then
            assertThat(details.details()).anyMatch(d -> d instanceof RetryInfoDetail);
        }

        @Test
        void dataDefinitionException_returns_500() {
            // when
            RootCauseResolution details = strategy.extractCauseDetails(
                    new DataDefinitionException("ddl error")
            );

            // then
            assertThat(details.httpStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        void dataMigrationException_returns_500() {
            // when
            RootCauseResolution details = strategy.extractCauseDetails(
                    new DataMigrationException("migration error")
            );

            // then
            assertThat(details.httpStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        void dataMigrationVerificationException_absorbed_by_MIGRATION_ERROR() {
            // when
            RootCauseResolution details = strategy.extractCauseDetails(
                    new DataMigrationVerificationException("verify error")
            );

            // then
            assertThat(findInfoDetail(details).getReason()).isEqualTo("MIGRATION_ERROR");
        }
    }

    @Nested
    @DisplayName("Fallback — generic DataAccessException")
    class Fallback {

        @Test
        void generic_DataAccessException_returns_500() {
            // when
            RootCauseResolution details = strategy.extractCauseDetails(
                    new DataAccessException("generic jooq error")
            );

            // then
            assertThat(details.httpStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        void generic_DataAccessException_reason_is_GENERIC() {
            // when
            RootCauseResolution details = strategy.extractCauseDetails(
                    new DataAccessException("generic jooq error")
            );

            // then
            assertThat(findInfoDetail(details).getReason()).isEqualTo("GENERIC");
        }

        @Test
        void generic_DataAccessException_is_not_retryable() {
            // when
            RootCauseResolution details = strategy.extractCauseDetails(
                    new DataAccessException("generic error")
            );

            // then
            assertThat(details.details()).noneMatch(d -> d instanceof RetryInfoDetail);
        }
    }

    @Nested
    @DisplayName("Diagnostic metadata")
    class DiagnosticMetadata {

        private final JooqExceptionStrategy strategyWithStackHints =
                new JooqExceptionStrategy(new StackHintExtractor(true, List.of()));

        @Test
        void contains_exceptionClass() {
            // when
            RootCauseResolution details = strategyWithStackHints.extractCauseDetails(new NoDataFoundException("no data"));
            Map<String, String> diag = findInfoDetail(details).getDiagnosticMetadata();

            // then
            assertThat(diag).containsEntry("exceptionClass", "org.jooq.exception.NoDataFoundException");
        }

        @Test
        void contains_causeMessage() {
            // when
            RootCauseResolution details = strategyWithStackHints.extractCauseDetails(
                    new MappingException("Cannot map record to MyPojo")
            );
            Map<String, String> diag = findInfoDetail(details).getDiagnosticMetadata();

            // then
            assertThat(diag.get("causeMessage")).contains("Cannot map record");
        }

        @Test
        void contains_stackHint() {
            // when
            RootCauseResolution details = strategyWithStackHints.extractCauseDetails(
                    new DataAccessException("sql error")
            );
            Map<String, String> diag = findInfoDetail(details).getDiagnosticMetadata();

            // then
            assertThat(diag).containsKey("stackHint");
        }

        @Test
        void diagnosticMetadata_is_unmodifiable() {
            // when
            RootCauseResolution details = strategyWithStackHints.extractCauseDetails(
                    new DataAccessException("error")
            );
            Map<String, String> diag = findInfoDetail(details).getDiagnosticMetadata();

            // then
            assertThatThrownBy(() -> diag.put("key", "value"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void contains_sqlState_when_SQLException_in_cause() {
            // given
            SQLException sqlEx = new SQLException("duplicate key", "23505", 0);
            DataAccessException jooqEx = new DataAccessException("sql error", sqlEx);

            // when
            RootCauseResolution details = strategyWithStackHints.extractCauseDetails(jooqEx);
            Map<String, String> diag = findInfoDetail(details).getDiagnosticMetadata();

            // then
            assertThat(diag).containsEntry("sqlState", "23505");
        }

        @Test
        void contains_sqlErrorCode_when_non_zero() {
            // given
            SQLException sqlEx = new SQLException("deadlock", "40P01", 1213);
            DataAccessException jooqEx = new DataAccessException("sql error", sqlEx);

            // when
            RootCauseResolution details = strategyWithStackHints.extractCauseDetails(jooqEx);
            Map<String, String> diag = findInfoDetail(details).getDiagnosticMetadata();

            // then
            assertThat(diag).containsEntry("sqlErrorCode", "1213");
        }

        @Test
        void no_sqlErrorCode_when_zero() {
            // given
            SQLException sqlEx = new SQLException("error", "HY000", 0);
            DataAccessException jooqEx = new DataAccessException("sql error", sqlEx);

            // when
            RootCauseResolution details = strategyWithStackHints.extractCauseDetails(jooqEx);
            Map<String, String> diag = findInfoDetail(details).getDiagnosticMetadata();

            // then
            assertThat(diag).doesNotContainKey("sqlErrorCode");
        }

        @Test
        void no_sqlState_when_no_SQLException_in_cause() {
            // when
            RootCauseResolution details = strategyWithStackHints.extractCauseDetails(
                    new MappingException("mapping error")
            );
            Map<String, String> diag = findInfoDetail(details).getDiagnosticMetadata();

            // then
            assertThat(diag).doesNotContainKey("sqlState");
        }

        @Test
        void constraintName_extracted_for_integrity_violation_postgresql() {
            // given
            IntegrityConstraintViolationException ex =
                    new IntegrityConstraintViolationException(
                            "ERROR: duplicate key value violates unique constraint \"uk_users_email\""
                    );

            // when
            RootCauseResolution details = strategyWithStackHints.extractCauseDetails(ex);
            Map<String, String> diag = findInfoDetail(details).getDiagnosticMetadata();

            // then
            assertThat(diag).containsEntry("constraintName", "uk_users_email");
        }

        @Test
        void constraintName_extracted_for_integrity_violation_mysql() {
            // given
            IntegrityConstraintViolationException ex =
                    new IntegrityConstraintViolationException(
                            "Duplicate entry '1' for key 'users.PRIMARY'"
                    );

            // when
            RootCauseResolution details = strategyWithStackHints.extractCauseDetails(ex);
            Map<String, String> diag = findInfoDetail(details).getDiagnosticMetadata();

            // then
            assertThat(diag).containsEntry("constraintName", "PRIMARY");
        }

        @Test
        void no_constraintName_for_non_integrity_types() {
            // when
            RootCauseResolution details = strategyWithStackHints.extractCauseDetails(
                    new DataAccessException("generic error")
            );
            Map<String, String> diag = findInfoDetail(details).getDiagnosticMetadata();

            // then
            assertThat(diag).doesNotContainKey("constraintName");
        }
    }

    @Nested
    @DisplayName("Domain")
    class Domain {

        @Test
        void domain_is_persistence_for_all_types() {
            // when / then
            assertThat(findInfoDetail(strategy.extractCauseDetails(
                    new NoDataFoundException("no data"))).getDomain())
                    .isEqualTo("persistence");

            assertThat(findInfoDetail(strategy.extractCauseDetails(
                    new DataChangedException("changed"))).getDomain())
                    .isEqualTo("persistence");

            assertThat(findInfoDetail(strategy.extractCauseDetails(
                    new MappingException("mapping"))).getDomain())
                    .isEqualTo("persistence");

            assertThat(findInfoDetail(strategy.extractCauseDetails(
                    new DataAccessException("generic"))).getDomain())
                    .isEqualTo("persistence");
        }
    }

    @Nested
    @DisplayName("Order")
    class Order {

        @Test
        void order_is_145() {
            // when / then
            assertThat(strategy.getOrder()).isEqualTo(145);
        }

        @Test
        void order_is_after_hibernate_and_before_data_access() {
            // given
            int hibernateOrder = 140;
            int dataAccessOrder = 150;

            // when
            int jooqOrder = strategy.getOrder();

            // then
            assertThat(jooqOrder).isGreaterThan(hibernateOrder);
            assertThat(jooqOrder).isLessThan(dataAccessOrder);
        }
    }

    private static ErrorInfoDetail findInfoDetail(RootCauseResolution details) {
        return details.details().stream()
                .filter(ErrorInfoDetail.class::isInstance)
                .map(ErrorInfoDetail.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("ErrorInfoDetail not found"));
    }

}
