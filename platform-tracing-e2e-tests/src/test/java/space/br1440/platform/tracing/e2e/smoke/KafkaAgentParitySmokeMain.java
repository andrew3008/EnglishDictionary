package space.br1440.platform.tracing.e2e.smoke;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.opentelemetry.api.OpenTelemetry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@EnableKafka
@SpringBootApplication
public class KafkaAgentParitySmokeMain {

    private static final CountDownLatch SINGLE_SUCCESS = new CountDownLatch(1);
    private static final CountDownLatch BATCH_SUCCESS = new CountDownLatch(1);
    private static final AtomicInteger SINGLE_ATTEMPTS = new AtomicInteger();
    private static final AtomicInteger BATCH_RECORDS = new AtomicInteger();

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
                "platform.tracing.suppression.suppress-micrometer-tracing", "true",
                "e2.kafka.single-topic", singleTopic,
                "e2.kafka.batch-topic", batchTopic));

        try (ConfigurableApplicationContext context = application.run()) {
            KafkaTemplate<String, String> template = context.getBean(KafkaTemplate.class);
            template.send(new ProducerRecord<>(singleTopic, "single-key", "single-value")).get(30, TimeUnit.SECONDS);
            template.send(withSensitiveHeader(batchTopic, "batch-1")).get(30, TimeUnit.SECONDS);
            template.send(withSensitiveHeader(batchTopic, "batch-2")).get(30, TimeUnit.SECONDS);
            template.flush();

            if (!SINGLE_SUCCESS.await(60, TimeUnit.SECONDS) || !BATCH_SUCCESS.await(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Kafka listeners did not complete");
            }
            System.out.println("KAFKA_E2:singleAttempts=" + SINGLE_ATTEMPTS.get());
            System.out.println("KAFKA_E2:batchRecords=" + BATCH_RECORDS.get());
            System.out.println("KAFKA_E2:openTelemetryBeans=" + context.getBeansOfType(OpenTelemetry.class).size());
            System.out.println("KAFKA_E2:COMPLETE");
            Thread.sleep(Duration.ofSeconds(3).toMillis());
        }
    }

    private static ProducerRecord<String, String> withSensitiveHeader(String topic, String value) {
        return new ProducerRecord<>(topic, null, null, null, value,
                List.of(new RecordHeader("authorization", "e2-kafka-secret".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
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

    @KafkaListener(topics = "${e2.kafka.single-topic}", groupId = "e2-single",
            containerFactory = "singleKafkaListenerContainerFactory")
    public void consumeSingle(ConsumerRecord<String, String> record) {
        if (SINGLE_ATTEMPTS.incrementAndGet() == 1) {
            throw new IllegalStateException("intentional first delivery failure");
        }
        SINGLE_SUCCESS.countDown();
    }

    @KafkaListener(topics = "${e2.kafka.batch-topic}", groupId = "e2-batch", batch = "true",
            containerFactory = "batchKafkaListenerContainerFactory")
    public void consumeBatch(List<ConsumerRecord<String, String>> records) {
        BATCH_RECORDS.set(records.size());
        BATCH_SUCCESS.countDown();
    }
}
