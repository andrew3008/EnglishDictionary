package space.br1440.platform.tracing.autoconfigure.control.policy;

/**
 * Single apply path for Config Server and Actuator adapters.
 * <p>
 * This interface is intentionally separate from {@link CompleteTracingPolicyReader}
 * and {@link AcceptedTracingPolicyReader}.
 * A consumer that only needs to read must receive only the read interface.
 * A consumer that only needs to apply must receive only this interface.
 * The implementation class implements all three, but the interfaces are independent.
 */
public interface TracingPolicySnapshotApplier {

    /**
     * Applies a complete eight-leaf snapshot through the private JMX boundary.
     *
     * @param expectedSequence current acceptedSequence for optimistic concurrency check
     * @param snapshot         complete eight-leaf snapshot, never partial
     * @param source           diagnostics/audit label
     * @return apply outcome; never null
     */
    TracingPolicyDeliveryResult applySnapshot(
            long expectedSequence,
            CompleteTracingPolicySnapshot snapshot,
            TracingPolicySource source);
}
