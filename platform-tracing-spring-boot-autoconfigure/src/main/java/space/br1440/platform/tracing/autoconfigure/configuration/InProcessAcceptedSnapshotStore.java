package space.br1440.platform.tracing.autoconfigure.configuration;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-process store of the last successfully applied accepted snapshot.
 * Updated by {@link PrivateJmxTracingPolicySnapshotClient} after each
 * APPLIED or UNCHANGED delivery outcome.
 * <p>
 * This is the source of truth for {@link AcceptedTracingPolicySnapshotReader}.
 * No new JMX members are added to the private MBean contract.
 * The Agent/App wire protocol is unchanged.
 */
public final class InProcessAcceptedSnapshotStore implements AcceptedTracingPolicySnapshotReader {

    private final AtomicReference<AcceptedSnapshotWithSequence> ref = new AtomicReference<>();

    public void update(AcceptedSnapshotWithSequence value) {
        ref.set(value);
    }

    @Override
    public Optional<AcceptedSnapshotWithSequence> currentComplete() {
        return Optional.ofNullable(ref.get());
    }
}
