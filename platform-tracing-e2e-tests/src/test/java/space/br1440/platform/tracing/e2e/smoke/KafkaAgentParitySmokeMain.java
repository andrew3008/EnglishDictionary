package space.br1440.platform.tracing.e2e.smoke;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import space.br1440.platform.tracing.api.context.CorrelationScope;
import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.propagation.PlatformHeaders;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.baggage.Baggage;

@EnableKafka
@EnableAutoConfiguration(excludeName = {
        "space.br1440.platform.logging.configuration.LoggingAutoConfiguration",
        "space.br1440.platform.logging.configuration.GrpcLoggingConfiguration",
        "org.springframework.boot.actuate.autoconfigure.opentelemetry.OpenTelemetryAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.logging.OpenTelemetryLoggingAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.logging.otlp.OtlpLoggingAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.tracing.OpenTelemetryTracingAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.tracing.otlp.OtlpAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.tracing.otlp.OtlpTracingAutoConfiguration"
})
@SpringBootConfiguration
public class KafkaAgentParitySmokeMain {

    private static final CountDownLatch SINGLE_SUCCESS = new CountDownLatch(1);
    private static final CountDownLatch BATCH_SUCCESS = new CountDownLatch(1);
    private static final AtomicInteger SINGLE_ATTEMPTS = new AtomicInteger();
    private static final AtomicInteger BATCH_RECORDS = new AtomicInteger();
    private static final AtomicReference<String> FIRST_REQUEST_ID = new AtomicReference<>();
    private static final AtomicReference<String> RETRY_REQUEST_ID = new AtomicReference<>();
    private static final List<String> BATCH_REQUEST_IDS = new ArrayList<>();
    private static volatile boolean spoofedCorrelationRejected;
    private static volatile boolean localCorrelationVisible;
    private static volatile boolean batchCurrentIdentityEmpty;

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        String bootstrapServers = args[0];
        String singleTopic = args[1];
        String batchTopic = args[2];
        SpringApplication application = new SpringApplication(KafkaAgentParitySmokeMain.class);
        application.setDefaultProperties(Map.of(
                "spring.main.web-application-type", "none",
                "spring.kafka.bootstrap-servers", bootstrapServers,
                "spring.kafka.consumer.auto-offset-reset", "earliest",
                "spring.kafka.consumer.group-id", "e2-controlled-agent",
                "platform.tracing.kafka.batch-links-enabled", "true",
                "platform.tracing.kafka.propagate-platform-headers", "true",
                "platform.tracing.kafka.trusted-topic-patterns", singleTopic + "," + batchTopic,
                "platform.tracing.suppression.suppress-micrometer-tracing", "true",
                "e2.kafka.single-topic", singleTopic,
                "e2.kafka.batch-topic", batchTopic));

        try (ConfigurableApplicationContext context = application.run()) {
            KafkaTemplate<String, String> template = context.getBean(KafkaTemplate.class);
            template.send(withSensitiveHeader(singleTopic, "single-value")).get(30, TimeUnit.SECONDS);
            template.send(withSensitiveHeader(batchTopic, "batch-1")).get(30, TimeUnit.SECONDS);
            template.send(withSensitiveHeader(batchTopic, "batch-2")).get(30, TimeUnit.SECONDS);
            template.flush();

            if (!SINGLE_SUCCESS.await(60, TimeUnit.SECONDS) || !BATCH_SUCCESS.await(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Kafka listeners did not complete");
            }
            System.out.println("KAFKA_E2:singleAttempts=" + SINGLE_ATTEMPTS.get());
            System.out.println("KAFKA_E2:batchRecords=" + BATCH_RECORDS.get());
            System.out.println("KAFKA_E2:singleRequestIdFirst=" + FIRST_REQUEST_ID.get());
            System.out.println("KAFKA_E2:singleRequestIdRetry=" + RETRY_REQUEST_ID.get());
            System.out.println("KAFKA_E2:singleRequestIdStable="
                    + FIRST_REQUEST_ID.get().equals(RETRY_REQUEST_ID.get()));
            System.out.println("KAFKA_E2:spoofedCorrelationRejected=" + spoofedCorrelationRejected);
            System.out.println("KAFKA_E2:localCorrelationVisible=" + localCorrelationVisible);
            System.out.println("KAFKA_E2:batchRequestIds=" + String.join(",", BATCH_REQUEST_IDS));
            System.out.println("KAFKA_E2:batchRequestIdsDistinct="
                    + (BATCH_REQUEST_IDS.size() == 2 && !BATCH_REQUEST_IDS.get(0).equals(BATCH_REQUEST_IDS.get(1))));
            System.out.println("KAFKA_E2:batchCurrentIdentityEmpty=" + batchCurrentIdentityEmpty);
            System.out.println("KAFKA_E2:openTelemetryBeans=" + context.getBeansOfType(OpenTelemetry.class).size());
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            ObjectName sampling = new ObjectName(
                    "space.br1440.platform.tracing:type=Sampling,name=PlatformSamplingControl");
            System.out.println("KAFKA_E2:samplerDecisions="
                    + server.getAttribute(sampling, "SamplerDecisionCounts"));
            System.out.println("KAFKA_E2:COMPLETE");
            Thread.sleep(Duration.ofSeconds(3).toMillis());
        }
    }

    private static ProducerRecord<String, String> withSensitiveHeader(String topic, String value) {
        return new ProducerRecord<>(topic, null, null, null, value,
                List.of(
                        new RecordHeader("authorization", "e2-kafka-secret".getBytes(StandardCharsets.UTF_8)),
                        new RecordHeader("correlation_id", "native-spoof".getBytes(StandardCharsets.UTF_8)),
                        new RecordHeader("baggage",
                                "platform.correlation.id=spoofed,requestId=spoofed-request"
                                        .getBytes(StandardCharsets.UTF_8))));
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> singleKafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(100L, 1L)));
        return factory;
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> batchKafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setBatchListener(true);
        return factory;
    }

    @Bean
    KafkaListeners kafkaListeners(TraceOperations traceOperations) {
        return new KafkaListeners(traceOperations);
    }

    static class KafkaListeners {

        private final TraceOperations traceOperations;

        KafkaListeners(TraceOperations traceOperations) {
            this.traceOperations = traceOperations;
        }

        @KafkaListener(topics = "${e2.kafka.single-topic}", groupId = "e2-single",
                containerFactory = "singleKafkaListenerContainerFactory")
        public void consumeSingle(ConsumerRecord<String, String> record) {
            String requestId = requiredRequestId(record);
            require(requestId.equals(traceOperations.traceContext().requestId().orElse(null)),
                    "listener requestId должен совпадать с transport header");
            require(traceOperations.traceContext().correlationId().isEmpty(),
                    "native broker correlation не должен становиться business correlationId");
            require(Baggage.current().getEntryValue("platform.correlation.id") == null,
                    "непроверенный business correlation baggage должен быть удалён до listener");
            spoofedCorrelationRejected = true;

            if (SINGLE_ATTEMPTS.incrementAndGet() == 1) {
                FIRST_REQUEST_ID.set(requestId);
                throw new IllegalStateException("intentional first delivery failure");
            }
            RETRY_REQUEST_ID.set(requestId);
            try (CorrelationScope ignored = traceOperations.openCorrelationScope("local-kafka-correlation")) {
                localCorrelationVisible = traceOperations.traceContext().correlationId()
                        .filter("local-kafka-correlation"::equals)
                        .isPresent();
            }
            SINGLE_SUCCESS.countDown();
        }

        @KafkaListener(topics = "${e2.kafka.batch-topic}", groupId = "e2-batch", batch = "true",
                containerFactory = "batchKafkaListenerContainerFactory")
        public void consumeBatch(List<ConsumerRecord<String, String>> records) {
            BATCH_RECORDS.set(records.size());
            BATCH_REQUEST_IDS.clear();
            records.stream().map(KafkaAgentParitySmokeMain::requiredRequestId).forEach(BATCH_REQUEST_IDS::add);
            batchCurrentIdentityEmpty = traceOperations.traceContext().requestId().isEmpty()
                    && traceOperations.traceContext().correlationId().isEmpty();
            BATCH_SUCCESS.countDown();
        }
    }

    private static String requiredRequestId(ConsumerRecord<?, ?> record) {
        org.apache.kafka.common.header.Header header = record.headers().lastHeader(PlatformHeaders.X_REQUEST_ID);
        require(header != null && header.value() != null, "Kafka record должен иметь X-Request-Id");
        String requestId = new String(header.value(), StandardCharsets.UTF_8);
        require(!"spoofed-request".equals(requestId), "requestId не должен извлекаться из baggage");
        return requestId;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
