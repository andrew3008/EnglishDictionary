package space.br1440.platform.tracing.autoconfigure.configuration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import space.br1440.platform.tracing.autoconfigure.jmx.privatecontrol.PrivateControlLocator;
import space.br1440.platform.tracing.autoconfigure.jmx.privatecontrol.PrivateControlOpenTypeCodec;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.util.Optional;

/**
 * Single private JMX implementation of all three read/write capabilities.
 * <p>
 * Implements:
 * - {@link TracingPolicySnapshotClient} (apply path)
 * - {@link AcceptedTracingPolicySnapshotReader} (internal complete snapshot read for Actuator adapter)
 * - {@link AcceptedTracingPolicyReader} (secret-safe projection for public read endpoint)
 * <p>
 * The three interfaces are independent. Spring wires them separately to different consumers.
 * Uses the private MBeanServer only. Does NOT use platform MBeanServer.
 */
public final class PrivateJmxTracingPolicySnapshotClient
        implements TracingPolicySnapshotClient,
                   AcceptedTracingPolicySnapshotReader,
                   AcceptedTracingPolicyReader {

    private static final Logger log = LoggerFactory.getLogger(PrivateJmxTracingPolicySnapshotClient.class);

    private static final String PRIVATE_CONTROL_OBJECT_NAME =
            "space.br1440.platform.tracing:type=Control,name=AggregatePolicyControl";

    private final PrivateControlLocator locator;
    private final PrivateControlOpenTypeCodec codec;

    private volatile MBeanServer privateServer;
    private volatile ObjectName controlObjectName;

    public PrivateJmxTracingPolicySnapshotClient(
            PrivateControlLocator locator,
            PrivateControlOpenTypeCodec codec) {
        this.locator = locator;
        this.codec = codec;
    }

    public void initialize() {
        try {
            this.privateServer = locator.locate();
            this.controlObjectName = ObjectName.getInstance(PRIVATE_CONTROL_OBJECT_NAME);
            log.info("PrivateJmxTracingPolicySnapshotClient initialized: ObjectName={}", PRIVATE_CONTROL_OBJECT_NAME);
        } catch (Exception e) {
            throw new TracingConfigurationGuardException(
                    "Cannot initialize snapshot client: private MBeanServer not available. " + e.getMessage());
        }
    }

    // --- TracingPolicySnapshotClient ---

    @Override
    public TracingPolicyDeliveryResult applySnapshot(
            long expectedSequence,
            CompleteTracingPolicySnapshot snapshot,
            TracingPolicySource source) {
        MBeanServer server = this.privateServer;
        ObjectName name = this.controlObjectName;
        if (server == null || name == null) {
            log.warn("applySnapshot called before initialization; source={}", source);
            return TracingPolicyDeliveryResult.FAILED;
        }
        Object encoded;
        try {
            encoded = codec.encode(snapshot);
        } catch (Exception e) {
            // Codec/schema mismatch or programming defect: fail-fast, not FAILED delivery result
            throw new TracingPolicyContractViolationException(
                    "Codec failed to encode snapshot: this is a programming defect, not a delivery error. " +
                    "Source: " + source + ". Error: " + e.getMessage(), e);
        }
        try {
            Object result = server.invoke(
                    name,
                    "applySnapshot",
                    new Object[]{expectedSequence, encoded, source.name()},
                    new String[]{long.class.getName(), "javax.management.openmbean.CompositeData", String.class.getName()}
            );
            return parseOutcome(result);
        } catch (Exception e) {
            // JMX transport error: return FAILED (recoverable delivery failure)
            log.error("applySnapshot JMX transport error: source={}, error={}", source, e.getMessage(), e);
            return TracingPolicyDeliveryResult.FAILED;
        }
    }

    // --- AcceptedTracingPolicySnapshotReader (internal, complete, for Actuator adapter) ---

    @Override
    public Optional<AcceptedSnapshotWithSequence> currentComplete() {
        MBeanServer server = this.privateServer;
        ObjectName name = this.controlObjectName;
        if (server == null || name == null) return Optional.empty();
        try {
            Object result = server.invoke(name, "readAcceptedComplete", null, null);
            if (result == null) return Optional.empty();
            return Optional.of(codec.decodeCompleteWithSequence(result));
        } catch (Exception e) {
            if (e instanceof TracingPolicyContractViolationException) throw (RuntimeException) e;
            log.debug("readAcceptedComplete failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    // --- AcceptedTracingPolicyReader (public, secret-safe, for Actuator read endpoint) ---

    @Override
    public Optional<AcceptedPolicyProjection> currentProjection() {
        MBeanServer server = this.privateServer;
        ObjectName name = this.controlObjectName;
        if (server == null || name == null) return Optional.empty();
        try {
            Object result = server.invoke(name, "readAcceptedProjection", null, null);
            if (result == null) return Optional.empty();
            return Optional.of(codec.decodeProjection(result));
        } catch (Exception e) {
            log.debug("readAcceptedProjection failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private TracingPolicyDeliveryResult parseOutcome(Object raw) {
        if (raw instanceof String s) {
            try {
                return TracingPolicyDeliveryResult.valueOf(s);
            } catch (IllegalArgumentException ex) {
                // Unknown outcome string from Agent: protocol mismatch = contract violation
                throw new TracingPolicyContractViolationException(
                        "Agent returned unknown applySnapshot outcome: '" + s + "'. " +
                        "This indicates a version mismatch between Spring library and Agent.");
            }
        }
        // null or wrong type: contract violation
        throw new TracingPolicyContractViolationException(
                "Agent returned unexpected applySnapshot result type: " +
                (raw == null ? "null" : raw.getClass().getName()));
    }
}
