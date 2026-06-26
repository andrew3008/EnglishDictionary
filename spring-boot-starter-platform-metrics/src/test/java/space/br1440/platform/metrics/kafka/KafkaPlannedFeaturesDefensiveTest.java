package space.br1440.platform.metrics.kafka;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import space.br1440.platform.metrics.configuration.KafkaMetricsConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("kafka-planned")
@DisplayName("Planned Kafka features defensive assert")
class KafkaPlannedFeaturesDefensiveTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KafkaMetricsConfiguration.class))
            .withPropertyValues("spring.kafka.bootstrap-servers=localhost:9092");

    @Test
    void consumerLagMeterBinderAbsent() {
        runner.run(context -> assertThat(context.getBeansOfType(Object.class).keySet())
                .noneMatch(name -> name.toLowerCase().contains("consumerlagmeterbinder")));
    }
}
