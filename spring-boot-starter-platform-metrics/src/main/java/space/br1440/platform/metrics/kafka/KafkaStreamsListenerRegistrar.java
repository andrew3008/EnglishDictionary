package space.br1440.platform.metrics.kafka;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.kafka.config.StreamsBuilderFactoryBean;
import org.springframework.kafka.streams.KafkaStreamsMicrometerListener;

import java.util.List;

/**
 * {@link BeanPostProcessor}, регистрирующий {@link KafkaStreamsMicrometerListener}
 * на каждый {@link StreamsBuilderFactoryBean} в контексте.
 *
 * <p>Каждый listener получает тег {@code streams.topology=<имя бина>}, что позволяет
 * multi-topology приложениям дифференцировать нативные {@code kafka.streams.*} метрики
 * в Grafana.</p>
 *
 * <p>Listener реализует {@link StreamsBuilderFactoryBean.Listener} и получает callback
 * при создании ({@code streamsAdded}) и удалении ({@code streamsRemoved}) экземпляра
 * {@code KafkaStreams}. В callback'е вызывается {@code KafkaStreamsMetrics.bindTo(MeterRegistry)},
 * что публикует нативные streams-метрики:</p>
 * <ul>
 *   <li>{@code kafka.streams.process.latency.avg}</li>
 *   <li>{@code kafka.streams.task.process.ratio}</li>
 *   <li>{@code kafka.streams.thread.commit.latency.avg}</li>
 *   <li>и другие.</li>
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public class KafkaStreamsListenerRegistrar implements BeanPostProcessor {

    static final String STREAMS_TOPOLOGY_TAG = "streams.topology";

    private final ObjectProvider<MeterRegistry> meterRegistryProvider;

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (!(bean instanceof StreamsBuilderFactoryBean factory)) {
            return bean;
        }

        boolean alreadyHasPlatformListener = factory.getListeners().stream()
                .anyMatch(KafkaStreamsMicrometerListener.class::isInstance);
        if (alreadyHasPlatformListener) {
            log.debug("StreamsBuilderFactoryBean '{}': KafkaStreamsMicrometerListener already registered, skipping", beanName);
            return bean;
        }

        MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        if (registry == null) {
            log.debug("StreamsBuilderFactoryBean '{}': MeterRegistry not available, skipping KafkaStreamsMicrometerListener registration", beanName);
            return bean;
        }

        KafkaStreamsMicrometerListener listener = new KafkaStreamsMicrometerListener(
                registry,
                List.of(Tag.of(STREAMS_TOPOLOGY_TAG, beanName))
        );
        factory.addListener(listener);

        log.debug("StreamsBuilderFactoryBean '{}': KafkaStreamsMicrometerListener registered with tag {}='{}'",
                beanName, STREAMS_TOPOLOGY_TAG, beanName);
        return bean;
    }
}