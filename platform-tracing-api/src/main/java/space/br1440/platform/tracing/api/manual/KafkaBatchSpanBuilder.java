package space.br1440.platform.tracing.api.manual;

/**
 * Kafka batch consumer span builder returned by {@link KafkaConsumerSpanBuilder#batch(String)}.
 * <p>
 * Batch processing spans should use {@link #root()} with pre-start {@link #linkedTo} or
 * {@link #fromRemoteContext} links to referenced message contexts.
 */
public interface KafkaBatchSpanBuilder extends PlatformSpanBuilder<KafkaBatchSpanBuilder> {
}
