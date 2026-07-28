package space.br1440.platform.tracing.autoconfigure.configuration;

/**
 * Single application-side runtime apply component shared by Config Server and Actuator adapters.
 * <p>
 * Config Server and Actuator MUST use the same instance of this interface.
 * There is exactly one private JMX infrastructure implementation.
 * Authority-specific writers (ConfigServerSnapshotWriter, ActuatorSnapshotWriter) are forbidden.
 */
public interface TracingPolicySnapshotClient extends AcceptedTracingPolicyReader {

    /**
     * Applies a complete Category A snapshot through the private JMX boundary.
     *
     * @param expectedSequence current acceptedSequence for optimistic concurrency check
     * @param snapshot         complete eight-leaf snapshot, never partial
     * @param source           diagnostics/audit label for the originating adapter
     * @return apply outcome; never null
     */
    TracingPolicyDeliveryResult applySnapshot(
            long expectedSequence,
            CompleteTracingPolicySnapshot snapshot,
            TracingPolicySource source);
}
