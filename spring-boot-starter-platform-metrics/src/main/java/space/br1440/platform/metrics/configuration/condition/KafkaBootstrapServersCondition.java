package space.br1440.platform.metrics.configuration.condition;

import lombok.NonNull;
import org.springframework.boot.autoconfigure.condition.ConditionMessage;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.ConfigurationCondition;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

/**
 * Условие активации {@link space.br1440.platform.metrics.configuration.KafkaMetricsConfiguration}:
 * Kafka считается сконфигурированной, если в окружении задан хотя бы один из стандартных
 * Spring Boot‑ключей с адресами брокеров (непустое значение).
 *
 * <p>Проверяются:</p>
 * <ul>
 *     <li>{@code spring.kafka.bootstrap-servers} — общее свойство;</li>
 *     <li>{@code spring.kafka.producer.bootstrap-servers} — переопределение для producer;</li>
 *     <li>{@code spring.kafka.consumer.bootstrap-servers} — переопределение для consumer;</li>
 *     <li>{@code spring.kafka.streams.bootstrap-servers} — переопределение для Kafka Streams.</li>
 * </ul>
 */
public class KafkaBootstrapServersCondition extends SpringBootCondition implements ConfigurationCondition {

    private static final String GLOBAL_BOOTSTRAP_SERVERS   = "spring.kafka.bootstrap-servers";
    private static final String PRODUCER_BOOTSTRAP_SERVERS = "spring.kafka.producer.bootstrap-servers";
    private static final String CONSUMER_BOOTSTRAP_SERVERS = "spring.kafka.consumer.bootstrap-servers";
    private static final String STREAMS_BOOTSTRAP_SERVERS  = "spring.kafka.streams.bootstrap-servers";

    private static final String[] BOOTSTRAP_SERVER_KEYS = {
            GLOBAL_BOOTSTRAP_SERVERS,
            PRODUCER_BOOTSTRAP_SERVERS,
            CONSUMER_BOOTSTRAP_SERVERS,
            STREAMS_BOOTSTRAP_SERVERS
    };

    @Override
    @NonNull
    public ConfigurationCondition.ConfigurationPhase getConfigurationPhase() {
        return ConfigurationCondition.ConfigurationPhase.PARSE_CONFIGURATION;
    }

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        ConditionMessage.Builder message = ConditionMessage.forCondition("OnKafkaBootstrapServers");

        if (context == null) {
            return ConditionOutcome.noMatch(message.because("environment is not available"));
        }

        Environment env = context.getEnvironment();
        for (String key : BOOTSTRAP_SERVER_KEYS) {
            if (StringUtils.hasText(env.getProperty(key))) {
                return ConditionOutcome.match(message.found("property").items(key));
            }
        }

        return ConditionOutcome.noMatch(
                message.didNotFind("property").items((Object[]) BOOTSTRAP_SERVER_KEYS)
        );
    }
}
