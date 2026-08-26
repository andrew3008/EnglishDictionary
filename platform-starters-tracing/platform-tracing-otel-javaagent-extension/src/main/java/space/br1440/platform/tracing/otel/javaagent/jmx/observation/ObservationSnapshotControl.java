package space.br1440.platform.tracing.otel.javaagent.jmx.observation;

/**
 * Управляемая точка публикации заранее подготовленного observation-снимка.
 */
public interface ObservationSnapshotControl {

    void publishObservationSnapshot();
}
