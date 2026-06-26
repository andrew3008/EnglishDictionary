package space.br1440.platform.errorhandling.e2e;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import space.br1440.platform.errorhandling.core.strategy.cause.messaging.KafkaBrokerExceptionStrategy;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.extension.RootCauseResolution;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

@Tag("testcontainers")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = KafkaStrategyE2ETest.TestConfig.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("KafkaBrokerExceptionStrategy — E2E (Testcontainers + Spring Kafka)")
class KafkaStrategyE2ETest {

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.kafka.producer.acks", () -> "all");
    }

    @Autowired
    private KafkaBrokerExceptionStrategy strategy;

    @Nested
    @DisplayName("Real broker connectivity failure")
    class RealBrokerConnectivityFailure {

        @Test
        @DisplayName("publish to unreachable bootstrap yields broker exception in cause chain")
        void unreachableBrokerPublish() {
            Throwable thrown = publishViaUnreachableBroker();
            assertThat(thrown).isNotNull();

            Throwable root = unwrapKafkaBrokerCause(thrown);
            assertThat(root).isNotNull();
            assertThat(strategy.supportsCause(root)).isTrue();
        }
    }

    @Nested
    @DisplayName("KafkaBrokerExceptionStrategy on real exception")
    class StrategyHandlesRealException {

        @Test
        @DisplayName("extractCauseDetails returns ErrorInfoDetail with kafka metadata")
        void extractCauseDetails_returnsErrorInfo() {
            Throwable thrown = publishViaUnreachableBroker();
            assertThat(thrown).isNotNull();

            RootCauseResolution resolution = strategy.extractCauseDetails(thrown);
            assertThat(resolution.details().stream().anyMatch(ErrorInfoDetail.class::isInstance)).isTrue();
        }
    }

    /**
     * Отдельный producer с недоступным bootstrap — брокер реально не отвечает
     * (в отличие от {@link KafkaTemplate} с рабочим Testcontainers-брокером,
     * где auto-create топика делает send успешным).
     */
    private static Throwable publishViaUnreachableBroker() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:1");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 3_000);
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 3_000);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 5_000);
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            return catchThrowable(() ->
                    producer.send(new ProducerRecord<>("e2e-unreachable-" + System.nanoTime(), "k", "v"))
                            .get(10, TimeUnit.SECONDS));
        }
    }

    private static Throwable unwrapKafkaBrokerCause(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current.getClass().getName().startsWith("org.apache.kafka")
                    || current.getClass().getName().startsWith("org.springframework.kafka")) {
                return current;
            }
            current = current.getCause();
        }
        return throwable;
    }

    @SpringBootConfiguration
    @Import(KafkaAutoConfiguration.class)
    static class TestConfig {

        @Bean
        StackHintExtractor stackHintExtractor() {
            return new StackHintExtractor(false, List.of());
        }

        @Bean
        KafkaBrokerExceptionStrategy kafkaBrokerExceptionStrategy(StackHintExtractor stackHintExtractor) {
            return new KafkaBrokerExceptionStrategy(true, stackHintExtractor);
        }
    }
}
