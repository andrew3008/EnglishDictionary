package space.br1440.platform.tracing.autoconfigure.control.policy.read;

import java.util.Optional;

public interface AcceptedTracingPolicyReader {

    Optional<AcceptedPolicyProjection> currentProjection();

}
