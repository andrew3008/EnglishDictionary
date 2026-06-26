package space.br1440.platform.errorhandling.core.strategy.cause.storage;

import org.hibernate.*;
import org.hibernate.id.IdentifierGenerationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.extension.RootCauseResolution;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;
import space.br1440.platform.errorhandling.model.error_detail.RetryInfoDetail;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("HibernateExceptionStrategy")
class HibernateExceptionStrategyTest {

    private HibernateExceptionStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new HibernateExceptionStrategy(new StackHintExtractor(false, List.of()));
    }

    @Nested
    @DisplayName("supportsCause")
    class SupportsCause {

        @Test
        void acceptsHibernateException() {
            // when / then
            assertThat(strategy.supportsCause(new HibernateException("generic"))).isTrue();
        }

        @Test
        void acceptsLazyInitializationException() {
            // when / then
            assertThat(strategy.supportsCause(
                    new LazyInitializationException("could not initialize proxy"))).isTrue();
        }

        @Test
        void rejectsJDBCException() {
            // given
            JDBCException jdbc = new JDBCException("JDBC error", new java.sql.SQLException("test"));

            // when / then
            assertThat(strategy.supportsCause(jdbc)).isFalse();
        }

        @Test
        void rejectsNonHibernateExceptions() {
            // when / then
            assertThat(strategy.supportsCause(new RuntimeException("not hibernate"))).isFalse();
        }
    }

    @Nested
    @DisplayName("classification — direct subtypes")
    class DirectClassification {

        @ParameterizedTest(name = "{0} -> {1} / {2}")
        @MethodSource("classificationCases")
        void classifiesCorrectly(String label,
                                  String expectedReason,
                                  HttpStatus expectedStatus,
                                  boolean expectedRetryable,
                                  Throwable cause) {
            // when
            RootCauseResolution resolution = strategy.extractCauseDetails(cause);

            // then
            assertThat(extractReason(resolution)).isEqualTo(expectedReason);
            assertThat(resolution.httpStatus()).isEqualTo(expectedStatus);

            boolean hasRetryInfo = resolution.details().stream()
                    .anyMatch(d -> d instanceof RetryInfoDetail);
            assertThat(hasRetryInfo).as("retryable=" + expectedRetryable).isEqualTo(expectedRetryable);
        }

        static Stream<Arguments> classificationCases() {
            return Stream.of(
                    Arguments.of("LazyInitializationException", "LAZY_INITIALIZATION",
                            HttpStatus.INTERNAL_SERVER_ERROR, false,
                            new LazyInitializationException("could not initialize proxy [com.example.Order#42] - no Session")),

                    Arguments.of("PropertyValueException", "PROPERTY_VALUE",
                            HttpStatus.UNPROCESSABLE_ENTITY, false,
                            new PropertyValueException("not-null property references a null", "Order", "customerName")),

                    Arguments.of("TransientObjectException", "TRANSIENT_OBJECT",
                            HttpStatus.INTERNAL_SERVER_ERROR, false,
                            new TransientObjectException("object references an unsaved transient instance: Order")),

                    Arguments.of("PersistentObjectException", "DETACHED_ENTITY",
                            HttpStatus.INTERNAL_SERVER_ERROR, false,
                            new PersistentObjectException("detached entity passed to persist: com.example.Order")),

                    Arguments.of("StaleStateException", "STALE_STATE",
                            HttpStatus.CONFLICT, true,
                            new StaleStateException("Row was updated or deleted")),

                    Arguments.of("StaleObjectStateException", "STALE_STATE",
                            HttpStatus.CONFLICT, true,
                            new StaleObjectStateException("com.example.Order", 42L)),

                    Arguments.of("NonUniqueObjectException", "DUPLICATE_OBJECT_IN_SESSION",
                            HttpStatus.CONFLICT, false,
                            new NonUniqueObjectException("duplicate", 1L, "com.example.Order")),

                    Arguments.of("NonUniqueResultException", "NON_UNIQUE_RESULT",
                            HttpStatus.INTERNAL_SERVER_ERROR, false,
                            new NonUniqueResultException(3)),

                    Arguments.of("QueryException", "QUERY_ERROR",
                            HttpStatus.INTERNAL_SERVER_ERROR, false,
                            new QueryException("could not resolve property: invalidField")),

                    Arguments.of("SessionException", "SESSION_CLOSED",
                            HttpStatus.INTERNAL_SERVER_ERROR, false,
                            new SessionException("Session is closed!")),

                    Arguments.of("MappingException", "MAPPING_ERROR",
                            HttpStatus.INTERNAL_SERVER_ERROR, false,
                            new MappingException("Unknown entity: com.example.Ghost")),

                    Arguments.of("IdentifierGenerationException", "ID_GENERATION_FAILED",
                            HttpStatus.INTERNAL_SERVER_ERROR, false,
                            new IdentifierGenerationException("ids for this class must be manually assigned")),

                    Arguments.of("TypeMismatchException", "TYPE_MISMATCH",
                            HttpStatus.INTERNAL_SERVER_ERROR, false,
                            new TypeMismatchException("Provided id of the wrong type")),

                    Arguments.of("Plain HibernateException", "GENERIC",
                            HttpStatus.INTERNAL_SERVER_ERROR, false,
                            new HibernateException("unknown error"))
            );
        }
    }

    @Nested
    @DisplayName("classification — message-based detection")
    class MessageBasedClassification {

        @Test
        void sharedCollectionReference() {
            // given
            HibernateException ex = new HibernateException(
                    "Found shared references to a collection: com.example.Order.items");

            // when
            RootCauseResolution resolution = strategy.extractCauseDetails(ex);

            // then
            assertThat(extractReason(resolution)).isEqualTo("SHARED_COLLECTION_REFERENCE");
        }

        @Test
        void identifierAltered() {
            // given
            HibernateException ex = new HibernateException(
                    "identifier of an instance of com.example.Order was altered from 1 to 2");

            // when
            RootCauseResolution resolution = strategy.extractCauseDetails(ex);

            // then
            assertThat(extractReason(resolution)).isEqualTo("IDENTIFIER_ALTERED");
        }
    }

    @Nested
    @DisplayName("diagnosticMetadata")
    class MetadataTests {

        @Test
        void alwaysContainsExceptionClassAndCauseMessage() {
            // given
            HibernateException ex = new HibernateException("some error");

            // when
            Map<String, String> metadata = extractDiagnosticMetadata(
                    strategy.extractCauseDetails(ex));

            // then
            assertThat(metadata).containsKey("exceptionClass");
            assertThat(metadata).containsKey("causeMessage");
            assertThat(metadata.get("exceptionClass"))
                    .isEqualTo(HibernateException.class.getName());
        }

        @Test
        void lazyInit_extractsAssociationType() {
            // given
            LazyInitializationException ex = new LazyInitializationException(
                    "could not initialize proxy [com.example.Order#42] - no Session");

            // when
            Map<String, String> metadata = extractDiagnosticMetadata(
                    strategy.extractCauseDetails(ex));

            // then
            assertThat(metadata.get("associationType")).isEqualTo("proxy");
        }

        @Test
        void lazyInit_collection_extractsAssociationType() {
            // given
            LazyInitializationException ex = new LazyInitializationException(
                    "failed to lazily initialize a collection of role: com.example.Order.items");

            // when
            Map<String, String> metadata = extractDiagnosticMetadata(
                    strategy.extractCauseDetails(ex));

            // then
            assertThat(metadata.get("associationType")).isEqualTo("collection");
        }

        @Test
        void propertyValue_extractsPropertyName() {
            // given
            PropertyValueException ex = new PropertyValueException(
                    "not-null property references a null or transient value",
                    "com.example.Order", "customerName");

            // when
            Map<String, String> metadata = extractDiagnosticMetadata(
                    strategy.extractCauseDetails(ex));

            // then
            assertThat(metadata).containsKey("propertyName");
            assertThat(metadata.get("propertyName")).isEqualTo("customerName");
        }

        @Test
        void domainIsPersistence() {
            // when
            RootCauseResolution resolution = strategy.extractCauseDetails(
                    new HibernateException("error"));

            // then
            ErrorInfoDetail info = resolution.details().stream()
                    .filter(d -> d instanceof ErrorInfoDetail)
                    .map(d -> (ErrorInfoDetail) d)
                    .findFirst()
                    .orElseThrow();
            assertThat(info.getDomain()).isEqualTo("persistence");
        }

        @Test
        void causeMessage_truncatedForLongMessages() {
            // given
            String longMessage = "x".repeat(500);
            HibernateException ex = new HibernateException(longMessage);

            // when
            Map<String, String> metadata = extractDiagnosticMetadata(
                    strategy.extractCauseDetails(ex));

            // then
            String causeMsg = metadata.get("causeMessage");
            assertThat(causeMsg.length()).isLessThan(500);
            assertThat(causeMsg).doesNotContain("x".repeat(400));
        }
    }

    @Nested
    @DisplayName("CWE-209 — entity name uses simpleName only")
    class SecurityCompliance {

        @Test
        void entityName_usesSimpleName_notFqcn() {
            // given
            StaleObjectStateException ex = new StaleObjectStateException(
                    "com.example.order.domain.OrderEntity", 42L);

            // when
            Map<String, String> metadata = extractDiagnosticMetadata(
                    strategy.extractCauseDetails(ex));

            // then
            if (metadata.containsKey("entityName")) {
                assertThat(metadata.get("entityName")).doesNotContain("com.example");
            }
        }
    }

    private static String extractReason(RootCauseResolution resolution) {
        return resolution.details().stream()
                .filter(d -> d instanceof ErrorInfoDetail)
                .map(d -> (ErrorInfoDetail) d)
                .map(ErrorInfoDetail::getReason)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No ErrorInfoDetail found"));
    }

    private static Map<String, String> extractDiagnosticMetadata(RootCauseResolution resolution) {
        return resolution.details().stream()
                .filter(d -> d instanceof ErrorInfoDetail)
                .map(d -> (ErrorInfoDetail) d)
                .map(ErrorInfoDetail::getDiagnosticMetadata)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No diagnosticMetadata found"));
    }
}
