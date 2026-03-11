package space.br1440.platform.healthchecks.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Health check для Kafka Producer, основанный на метрике {@code connection-count}
 * из группы {@code producer-metrics}.
 *
 * <h3>Почему не {@code producer.send()} в тестовый топик</h3>
 * <p>Отправка тестового сообщения в health check — антипаттерн:
 * каждый вызов создаёт side effect (сообщение в топике), вызывает сетевой overhead
 * и нарушает принцип идемпотентности health check.
 * При интервале Kubernetes-пробы в 10 секунд это ~8 640 тестовых сообщений в сутки
 * на один инстанс. Spring Boot отозвал свой {@code KafkaHealthIndicator} в 2018 году
 * именно из-за проблем зрелости подхода, основанного на write-операциях
 * (<a href="https://github.com/spring-projects/spring-boot/issues/12225">spring-boot#12225</a>).
 *
 * <h3>Почему {@code connection-count}</h3>
 * <p>{@code connection-count} — стандартная JMX-метрика Kafka Producer, отражающая
 * количество активных TCP-соединений с брокерами. Значение {@code > 0} означает,
 * что producer-клиент приложения имеет активное соединение с кластером.
 * Этот подход используется по умолчанию в SmallRye Reactive Messaging (Quarkus)
 * (<a href="https://smallrye.io/smallrye-reactive-messaging/4.7.0/kafka/health/">SmallRye Kafka Health</a>)
 * и рекомендован как lightweight read-only альтернатива send-based проверкам
 * (<a href="https://blog.rahulkonduru.com/posts/custom-kafka-health-indicator-spring-boot/">Custom Kafka Health Indicator in Spring Boot</a>).
 *
 * <h3>Фильтрация по группе {@code producer-metrics}</h3>
 * <p>Kafka публикует метрику {@code connection-count} в нескольких группах:
 * {@code producer-metrics} (агрегат по всему клиенту) и {@code producer-node-metrics} (per-broker соединения).
 * Фильтр по группе {@code producer-metrics} гарантирует,
 * что берётся агрегированное значение, а не счётчик отдельного брокера
 * (<a href="https://docs.confluent.io/platform/current/kafka/producer-metrics.html">Confluent Producer Metrics Reference</a>).
 *
 * @see KafkaConsumerHealthCheck
 * @see <a href="https://github.com/spring-projects/spring-boot/issues/12225">Spring Boot: Revert Kafka health indicator</a>
 * @see <a href="https://smallrye.io/smallrye-reactive-messaging/4.7.0/kafka/health/">SmallRye Kafka Health Checks</a>
 * @see <a href="https://docs.confluent.io/platform/current/kafka/producer-metrics.html">Confluent Producer Metrics Reference</a>
 */
@Slf4j
@RequiredArgsConstructor
public class KafkaProducerHealthCheck {

    private static final String CONNECTION_COUNT_METRIC = "connection-count";
    private static final String KAFKA_CLIENT_METRICS_GROUP = "producer-metrics";

    private final KafkaTemplate<String, String> kafkaTemplate;

    public void check(Map<String, Object> details) {
        Map<String, Object> producerDetails = new LinkedHashMap<>();

        try {
            double connectionCount = resolveConnectionCount();
            producerDetails.put("status", connectionCount > 0 ? "reachable" : "no_connections");
            producerDetails.put("connectionCount", (long) connectionCount);
        } catch (Exception e) {
            producerDetails.put("status", "error");
            producerDetails.put("error", e.getMessage());
            producerDetails.put("errorType", e.getClass().getSimpleName());
        }

        details.put("producer", producerDetails);
    }

    private double resolveConnectionCount() {
        return kafkaTemplate.metrics().entrySet().stream()
                .filter(e -> CONNECTION_COUNT_METRIC.equals(e.getKey().name())
                        && KAFKA_CLIENT_METRICS_GROUP.equals(e.getKey().group()))
                .map(e -> (Double) e.getValue().metricValue())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Metric '%s' not found in producer metrics".formatted(CONNECTION_COUNT_METRIC)
                ));
    }
}
