package space.br1440.platform.tracing.otel.javaagent.jmx.observation;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeType;

public final class ObservationSnapshotStore {

    private final CompositeType snapshotType;
    private final AtomicReference<CompositeData> published = new AtomicReference<>();

    public ObservationSnapshotStore(CompositeType snapshotType) {
        this.snapshotType = Objects.requireNonNull(snapshotType, "snapshotType");
    }

    public void publish(Map<String, Object> values) {
        published.set(JmxOpenTypeSupport.compositeData(snapshotType, values));
    }

    public CompositeData current() {
        return published.get();
    }
}
