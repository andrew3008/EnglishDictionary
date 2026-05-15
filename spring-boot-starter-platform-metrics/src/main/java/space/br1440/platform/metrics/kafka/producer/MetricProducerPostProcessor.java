package space.br1440.platform.metrics.kafka.producer;

import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.producer.Producer;
import org.springframework.kafka.core.ProducerPostProcessor;
import space.br1440.platform.metrics.PlatformMetrics;

@RequiredArgsConstructor
public class MetricProducerPostProcessor implements ProducerPostProcessor<Object, Object> {

    private final PlatformMetrics platformMetrics;

    @Override
    public Producer<Object, Object> apply(Producer<Object, Object> producer) {
        return new DecoratedProducer(producer, platformMetrics);
    }
}
