package space.br1440.platform.errorhandling.core.strategy.top;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.KafkaException;
import space.br1440.platform.errorhandling.core.resolver.CauseChainResolver;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.extension.ErrorHandlingContext;
import space.br1440.platform.errorhandling.extension.LogPolicy;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;
import space.br1440.platform.errorhandling.model.error_detail.RetryInfoDetail;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("KafkaExceptionStrategy")
class KafkaExceptionStrategyTest {

    private KafkaExceptionStrategy strategy;

    private static ErrorHandlingContext context(String path) {
        return ErrorHandlingContext.of(
                new ErrorHandlingProperties(), path, new StackHintExtractor(false, List.of()));
    }

    @BeforeEach
    void setUp() {
        CauseChainResolver resolver = new CauseChainResolver(Collections.emptyList());
        strategy = new KafkaExceptionStrategy(resolver, 5);
    }

    @Nested
    @DisplayName("supports")
    class Supports {

        @Test
        void acceptsKafkaException() {
            // when / then
            assertThat(strategy.supports(new KafkaException("kafka error"))).isTrue();
        }

        @Test
        void rejectsNonKafkaExceptions() {
            // when / then
            assertThat(strategy.supports(new RuntimeException("not kafka"))).isFalse();
        }
    }

    @Nested
    @DisplayName("classification")
    class Classification {

        @Test
        void plainKafkaException_classifiedAsGeneric() {
            // when
            ErrorEntry entry = strategy.handle(
                    new KafkaException("send failed"), context("/api/send"));

            // then
            assertThat(entry.getHttpStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
            assertThat(extractReason(entry)).isEqualTo("KAFKA_ERROR");
        }

        @Test
        void kafkaException_withTimeoutCause_classifiedAsBrokerTimeout() {
            // given
            org.apache.kafka.common.errors.TimeoutException kafkaTimeout =
                    new org.apache.kafka.common.errors.TimeoutException("Expiring 1 record(s)");
            KafkaException ex = new KafkaException("send failed",
                    new java.util.concurrent.ExecutionException(kafkaTimeout));

            // when
            ErrorEntry entry = strategy.handle(ex, context("/api/send"));

            // then
            assertThat(entry.getHttpStatus()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
            assertThat(extractReason(entry)).isEqualTo("BROKER_TIMEOUT");
            assertThat(entry.getDetails().stream().anyMatch(d -> d instanceof RetryInfoDetail)).isTrue();
        }

        @Test
        void genericKafkaException_isRetryable() {
            // when
            ErrorEntry entry = strategy.handle(
                    new KafkaException("send failed"), context("/"));

            // then
            boolean hasRetryInfo = entry.getDetails().stream()
                    .anyMatch(d -> d instanceof RetryInfoDetail);
            assertThat(hasRetryInfo).isTrue();
        }
    }

    @Nested
    @DisplayName("diagnosticMetadata")
    class MetadataStructure {

        @Test
        void containsExceptionClassAndKafkaErrorType() {
            // when
            ErrorEntry entry = strategy.handle(
                    new KafkaException("error"), context("/"));
            Map<String, String> metadata = extractDiagnosticMetadata(entry);

            // then
            assertThat(metadata).containsKey("exceptionClass");
            assertThat(metadata).containsKey("kafkaErrorType");
            assertThat(metadata.get("kafkaErrorType")).isEqualTo("KAFKA_ERROR");
        }

        @Test
        void domainIsKafka() {
            // when
            ErrorEntry entry = strategy.handle(
                    new KafkaException("error"), context("/"));

            // then
            ErrorInfoDetail info = extractFirstInfoDetail(entry);
            assertThat(info.getDomain()).isEqualTo("kafka");
        }

        @Test
        void clientMetadata_containsBrokerTypeAndOperation() {
            // when
            ErrorEntry entry = strategy.handle(
                    new KafkaException("error"), context("/"));

            // then
            ErrorInfoDetail info = extractFirstInfoDetail(entry);
            assertThat(info.getClientMetadata())
                    .containsEntry("brokerType", "KAFKA")
                    .containsEntry("operation", "SEND");
        }
    }

    @Nested
    @DisplayName("logPolicy")
    class LogPolicyTests {

        @Test
        void serverError_returnsError() {
            // given
            ErrorEntry entry = strategy.handle(
                    new KafkaException("send failed"), context("/"));

            // when / then
            assertThat(strategy.logPolicy(new KafkaException("x"), entry)).isEqualTo(LogPolicy.ERROR);
        }
    }

    @Nested
    @DisplayName("CWE-209")
    class SecurityCompliance {

        @Test
        void publicMessage_neverContainsBrokerDetails() {
            // given
            KafkaException ex = new KafkaException(
                    "Error sending to kafka-broker.internal:9092 topic=payments");

            // when
            ErrorEntry entry = strategy.handle(ex, context("/"));

            // then
            assertThat(entry.getMessage()).doesNotContain("kafka-broker.internal");
            assertThat(entry.getMessage()).doesNotContain("payments");
        }
    }

    private static String extractReason(ErrorEntry entry) {
        return extractFirstInfoDetail(entry).getReason();
    }

    private static Map<String, String> extractDiagnosticMetadata(ErrorEntry entry) {
        return extractFirstInfoDetail(entry).getDiagnosticMetadata();
    }

    private static ErrorInfoDetail extractFirstInfoDetail(ErrorEntry entry) {
        return entry.getDetails().stream()
                .filter(d -> d instanceof ErrorInfoDetail)
                .map(d -> (ErrorInfoDetail) d)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No ErrorInfoDetail found"));
    }
}
