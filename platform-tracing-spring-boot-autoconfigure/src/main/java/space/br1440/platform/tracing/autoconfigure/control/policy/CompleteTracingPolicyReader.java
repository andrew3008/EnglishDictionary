package space.br1440.platform.tracing.autoconfigure.control.policy;

import java.util.Optional;

public interface CompleteTracingPolicyReader {

    Optional<AcceptedSnapshotWithSequence> currentComplete();

}
