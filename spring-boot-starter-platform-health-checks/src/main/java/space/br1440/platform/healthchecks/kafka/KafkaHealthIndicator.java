package space.br1440.platform.healthchecks.kafka;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.common.Node;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.util.CollectionUtils;
import space.br1440.platform.healthchecks.async.AsyncHealthIndicator;
import space.br1440.platform.healthchecks.common.HealthProbe;
import space.br1440.platform.healthchecks.common.ProbeAware;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Health indicator для Apache Kafka, реализующий read-only проверку кластера
 * на основе {@link org.apache.kafka.clients.admin.AdminClient} и метрик producer-клиента.
 *
 * <h3>Архитектурные решения</h3>
 *
 * <p><b>Почему не producer.send() и не KafkaConsumer.poll()</b><br>
 * Spring Boot отозвал встроенный {@code KafkaHealthIndicator} в 2018 году
 * с формулировкой «not mature enough to provide out of the box»
 * (<a href="https://github.com/spring-projects/spring-boot/issues/12225">spring-boot#12225</a>).
 * Запрос на его восстановление был отклонён в 2024 году
 * (<a href="https://github.com/spring-projects/spring-boot/issues/41185">spring-boot#41185</a>).
 * Write-операции в health check нарушают принцип идемпотентности: при интервале
 * Kubernetes-пробы в 10 секунд это ~8 640 тестовых сообщений в сутки на один инстанс,
 * а {@code KafkaConsumer.poll()} вызывает group rebalance при каждой проверке.
 *
 * <p><b>Что проверяет этот индикатор</b>
 * <ul>
 *   <li><b>Cluster</b> — {@code AdminClient.describeCluster}: доступность кластера,
 *       количество брокеров и cluster ID. Рекомендован сообществом как «cleaner and
 *       efficient way» для health check Kafka
 *       (<a href="https://stackoverflow.com/q/59287340">Stack Overflow</a>)</li>
 *   <li><b>Producer</b> — метрика {@code connection-count} из группы
 *       {@code producer-metrics}: наличие активных TCP-соединений producer-клиента
 *       с брокерами без единого сетевого запроса. Используется по умолчанию
 *       в SmallRye Reactive Messaging (Quarkus)
 *       (<a href="https://smallrye.io/smallrye-reactive-messaging/4.7.0/kafka/health/">SmallRye Kafka Health</a>)</li>
 *   <li><b>Consumer</b> — offset-based forward progress через
 *       {@code AdminClient.listConsumerGroupOffsets} + {@code AdminClient.listOffsets}:
 *       committed offset и суммарный lag consumer group без создания consumer.
 *       Cloudflare и PagerDuty внедрили этот подход в production, что привело к снижению ложных алертов на 50%
 *       (<a href="https://blog.cloudflare.com/intelligent-automatic-restarts-for-unhealthy-kafka-consumers/">Cloudflare</a>,
 *       <a href="https://www.pagerduty.com/eng/kafka-health-checks/">PagerDuty</a>)</li>
 *   <li><b>Topics</b> — {@code AdminClient.describeTopics}: проверка offline partitions
 *       (leader = null) и under-replicated partitions (ISR &lt; replicas).
 *       Confluent рекомендует алертить при offline partitions &gt; 0 как на P1-инцидент
 *       (<a href="https://docs.confluent.io/platform/current/kafka/post-deployment.html">Confluent: Best Practices for Kafka Production</a>)</li>
 * </ul>
 *
 * @see <a href="https://github.com/spring-projects/spring-boot/issues/12225">Spring Boot: Revert Kafka health indicator</a>
 * @see <a href="https://www.pagerduty.com/eng/kafka-health-checks/">PagerDuty: Writing Intelligent Health Checks for Kafka Services</a>
 * @see <a href="https://blog.cloudflare.com/intelligent-automatic-restarts-for-unhealthy-kafka-consumers/">Cloudflare: Intelligent Kafka Consumer Restarts</a>
 * @see <a href="https://docs.confluent.io/platform/current/health-plus/index.html">Confluent Health+</a>
 */
@Slf4j
public class KafkaHealthIndicator extends AsyncHealthIndicator implements ProbeAware {

    private final AdminClient adminClient;
    private final KafkaHealthProperties properties;
    private final KafkaProducerHealthCheck producerHealthCheck;
    private final KafkaConsumerHealthCheck consumerHealthCheck;
    private final KafkaTopicHealthCheck topicHealthCheck;

    public KafkaHealthIndicator(AdminClient adminClient,
                                KafkaHealthProperties properties,
                                KafkaProducerHealthCheck producerHealthCheck,
                                KafkaConsumerHealthCheck consumerHealthCheck,
                                KafkaTopicHealthCheck topicHealthCheck) {
        super(properties.getAsync());

        this.adminClient = adminClient;
        this.properties = properties;
        this.producerHealthCheck = producerHealthCheck;
        this.consumerHealthCheck = consumerHealthCheck;
        this.topicHealthCheck = topicHealthCheck;
    }

    @Override
    public Set<HealthProbe> getProbes() {
        return properties.getProbes();
    }

    @Override
    protected Health doHealthCheck() throws Exception {
        Map<String, Object> details = new LinkedHashMap<>();
        Health.Builder builder = Health.up();

        checkCluster(details);
        if (details.containsKey("clusterWarning")) {
            builder.status(Status.OUT_OF_SERVICE);
        }

        checkProducer(details);
        checkConsumer(details);
        checkTopics(builder, details);

        return builder.withDetails(details).build();
    }

    @Override
    protected Status getFailureHealthCheckStatus() {
        return Status.DOWN;
    }

    private void checkCluster(Map<String, Object> details) throws Exception {
        long timeoutSeconds = properties.getTimeout().getRequestTimeout().toSeconds();
        DescribeClusterResult result = adminClient.describeCluster();

        Collection<Node> nodes = result.nodes().get(timeoutSeconds, TimeUnit.SECONDS);
        String clusterId = result.clusterId().get(timeoutSeconds, TimeUnit.SECONDS);

        details.put("brokerCount", nodes.size());
        details.put("clusterId", clusterId != null ? clusterId : "unknown");

        if (nodes.size() < properties.getCluster().getMinBrokerCount()) {
            details.put("clusterWarning", "Broker count (%d) is below minimum (%d)"
                    .formatted(nodes.size(), properties.getCluster().getMinBrokerCount()));
        }
    }

    private void checkProducer(Map<String, Object> details) {
        if (!properties.getProducer().isEnabled()) {
            return;
        }

        if (producerHealthCheck == null) {
            details.put("producer", Map.of("status", "error", "error", "KafkaTemplate not available"));
            return;
        }

        producerHealthCheck.check(details);
    }

    private void checkConsumer(Map<String, Object> details) {
        if (!properties.getConsumer().isEnabled()) {
            return;
        }

        consumerHealthCheck.check(adminClient, details);
    }

    private void checkTopics(Health.Builder builder, Map<String, Object> details) {
        KafkaHealthProperties.Topics topicsConfig = properties.getTopics();
        if (!topicsConfig.isEnabled()) {
            return;
        }

        List<String> monitored = topicsConfig.getMonitoredTopics();
        if (CollectionUtils.isEmpty(monitored)) {
            return;
        }

        try {
            topicHealthCheck.check(adminClient, builder, details);
        } catch (Exception e) {
            builder.status(Status.OUT_OF_SERVICE);
            details.put("topicsError", e.getMessage());
            details.put("topicsErrorType", e.getClass().getSimpleName());
        }
    }
}
