package space.br1440.platform.tracing.autoconfigure.control.policy;

import java.util.Optional;

/**
 * Secret-safe read capability for external Actuator diagnostic exposure.
 * <p>
 * Returns only the public projection (no route ratio values, no secrets).
 * The Actuator read endpoint depends only on this interface.
 * Must NOT return route ratio maps or internal state beyond what is safe for
 * external diagnostic consumption.
 */
public interface AcceptedTracingPolicyReader {
    Optional<AcceptedPolicyProjection> currentProjection();
}
