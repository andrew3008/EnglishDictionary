package space.br1440.platform.metrics.configuration;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.streams.KafkaStreams;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke-тесты условий активации {@link KafkaMetricsConfiguration} через {@link ApplicationContextRunner}.
 *
 * <p>Проверяет, что конфигурация включается и выключается строго при ожидаемых условиях,
 * не затрагивая остальные части контекста.</p>
 */
class KafkaMetricsConfigurationConditionsTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KafkaMetricsConfiguration.class));

    // ──────────────────────────────────────────────────────────────────────────────────────────────
    // Сценарий 1: Kafka не сконфигурирована — конфигурация метрик не активируется.
    // ──────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void configurationIsInactiveWhenNoKafkaProperty() {
        runner
                .withBean(SimpleMeterRegistry.class)
                .run(ctx -> assertThat(ctx)
                        .doesNotHaveBean("micrometerConsumerListener")
                        .doesNotHaveBean("micrometerProducerListener")
                        .doesNotHaveBean("kafkaFactoryListenerRegistrar"));
    }

    // ──────────────────────────────────────────────────────────────────────────────────────────────
    // Сценарий 2: Kafka задана, но MeterRegistry отсутствует — мягкий пропуск без исключений.
    // ──────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void configurationIsInactiveWhenMeterRegistryMissing() {
        runner
                .withPropertyValues("spring.kafka.bootstrap-servers=localhost:9092")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean("micrometerConsumerListener");
                    assertThat(ctx).doesNotHaveBean("kafkaFactoryListenerRegistrar");
                    // Контекст не должен упасть — ни исключений, ни ошибок старта.
                    assertThat(ctx).hasNotFailed();
                });
    }

    // ──────────────────────────────────────────────────────────────────────────────────────────────
    // Сценарий 3: Kafka задана + MeterRegistry присутствует — конфигурация активна.
    // ──────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void configurationIsActiveWhenKafkaAndMeterRegistryPresent() {
        runner
                .withPropertyValues("spring.kafka.bootstrap-servers=localhost:9092")
                .withBean(SimpleMeterRegistry.class)
                .run(ctx -> assertThat(ctx)
                        .hasSingleBean(org.springframework.kafka.core.MicrometerConsumerListener.class)
                        .hasSingleBean(org.springframework.kafka.core.MicrometerProducerListener.class)
                        .hasBean("kafkaFactoryListenerRegistrar"));
    }

    // ──────────────────────────────────────────────────────────────────────────────────────────────
    // Сценарий 4: kafka-streams отсутствует в classpath — kafkaStreamsListenerRegistrar не создаётся.
    // ──────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void streamsRegistrarIsAbsentWhenKafkaStreamsNotInClasspath() {
        runner
                .withPropertyValues("spring.kafka.bootstrap-servers=localhost:9092")
                .withBean(SimpleMeterRegistry.class)
                // Эмулируем отсутствие kafka-streams в classpath через FilteredClassLoader.
                .withClassLoader(new FilteredClassLoader(KafkaStreams.class))
                .run(ctx -> assertThat(ctx).doesNotHaveBean("kafkaStreamsListenerRegistrar"));
    }

    // ──────────────────────────────────────────────────────────────────────────────────────────────
    // Сценарий 5: kafka-streams присутствует в classpath — kafkaStreamsListenerRegistrar создаётся.
    // ──────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void streamsRegistrarIsCreatedWhenKafkaStreamsInClasspath() {
        runner
                .withPropertyValues("spring.kafka.bootstrap-servers=localhost:9092")
                .withBean(SimpleMeterRegistry.class)
                .run(ctx -> assertThat(ctx).hasBean("kafkaStreamsListenerRegistrar"));
    }
}
