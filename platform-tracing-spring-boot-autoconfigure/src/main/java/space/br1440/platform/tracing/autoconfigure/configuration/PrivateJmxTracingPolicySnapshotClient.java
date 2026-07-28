package space.br1440.platform.tracing.autoconfigure.configuration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import space.br1440.platform.tracing.autoconfigure.jmx.privatecontrol.PrivateControlLocator;
import space.br1440.platform.tracing.autoconfigure.jmx.privatecontrol.PrivateControlOpenTypeCodec;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.util.Optional;

/**
 * Single private JMX infrastructure implementation of {@link TracingPolicySnapshotClient}.
 * <p>
 * Both Config Server and Actuator adapters use this same instance.
 * There must be exactly one bean of this type in the application context.
 * <p>
 * Uses the private MBeanServer discovered via {@link PrivateControlLocator}.
 * Does NOT use the platform MBeanServer. Does NOT call legacy domain mutations.
 */
public final class PrivateJmxTracingPolicySnapshotClient implements TracingPolicySnapshotClient {

    private static final Logger log = LoggerFactory.getLogger(PrivateJmxTracingPolicySnapshotClient.class);

    // ObjectName of the private aggregate writer registered in P2
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

    // Called at context startup after private MBeanServer is confirmed available
    public void initialize() {
        try {
            this.privateServer = locator.locate();
            this.controlObjectName = ObjectName.getInstance(PRIVATE_CONTROL_OBJECT_NAME);
            log.info("PrivateJmxTracingPolicySnapshotClient: private MBeanServer located, ObjectName={}",
                    PRIVATE_CONTROL_OBJECT_NAME);
        } catch (Exception e) {
            log.error("PrivateJmxTracingPolicySnapshotClient: failed to locate private MBeanServer", e);
            throw new TracingConfigurationGuardException(
                    "Cannot initialize policy snapshot client: private MBeanServer not available. " + e.getMessage());
        }
    }

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
        try {
            Object encoded = codec.encode(snapshot);
            Object result = server.invoke(
                    name,
                    "applySnapshot",
                    new Object[]{expectedSequence, encoded, source.name()},
                    new String[]{long.class.getName(), "javax.management.openmbean.CompositeData", String.class.getName()}
            );
            return parseOutcome(result);
        } catch (Exception e) {
            log.error("applySnapshot failed: source={}, error={}", source, e.getMessage(), e);
            return TracingPolicyDeliveryResult.FAILED;
        }
    }

    @Override
    public Optional<AcceptedTracingPolicyView> current() {
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
                log.warn("Unknown apply outcome from Agent: '{}'", s);
            }
        }
        return TracingPolicyDeliveryResult.FAILED;
    }
}
