package space.br1440.platform.tracing.autoconfigure.configuration;

import java.util.Optional;

/**
 * Read-only capability for observing the current accepted tracing policy.
 * <p>
 * Consumers receive only this interface; they cannot obtain write capability through it.
 * The read endpoint ({@link space.br1440.platform.tracing.autoconfigure.configuration.actuator.TracingActuatorEndpoint})
 * depends only on this interface, not on the full snapshot client.
 * <p>
 * The authoritative source is Agent accepted state. Spring-bound properties
 * are NOT authoritative and must not be substituted.
 */
public interface AcceptedTracingPolicyReader {
    /**
     * Returns the current accepted policy snapshot.
     * Returns empty if Agent is not yet reachable or no snapshot has been applied.
     */
    Optional<AcceptedTracingPolicyView> current();
}
