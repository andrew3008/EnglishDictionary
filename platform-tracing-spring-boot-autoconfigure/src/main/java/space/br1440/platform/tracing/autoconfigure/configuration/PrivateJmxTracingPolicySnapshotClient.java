package space.br1440.platform.tracing.autoconfigure.configuration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import space.br1440.platform.tracing.autoconfigure.jmx.privatecontrol.PrivateControlLocator;
import space.br1440.platform.tracing.autoconfigure.jmx.privatecontrol.PrivateControlOpenTypeCodec;

import javax.management.MBeanException;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.ReflectionException;
import javax.management.RuntimeMBeanException;

/**
 * Private JMX client. Implements apply path + secret-safe projection read.
 * <p>
 * E7/P0 wire contract unchanged:
 *   1 mutation : applySnapshot
 *   6 marker reads : existing approved operations
 * <p>
 * No new JMX members. readAcceptedComplete is served by
 * {@link InProcessAcceptedSnapshotStore}, NOT by a new JMX call.
 * <p>
 * Exception classification:
 *   Agent-side contract/invariant failure (MBeanException / RuntimeMBeanException
 *   / ReflectionException wrapping non-transport cause) → TracingPolicyContractViolationException (fail-fast)
 *   JMX transport / instance-not-found / IO failure → FAILED (recoverable delivery)
 */
public final class PrivateJmxTracingPolicySnapshotClient
        implements TracingPolicySnapshotClient,
                   AcceptedTracingPolicyReader {

    private static final Logger log = LoggerFactory.getLogger(PrivateJmxTracingPolicySnapshotClient.class);

    private static final String PRIVATE_CONTROL_OBJECT_NAME =
            "space.br1440.platform.tracing:type=Control,name=AggregatePolicyControl";

    private final PrivateControlLocator locator;
    private final PrivateControlOpenTypeCodec codec;
    private final InProcessAcceptedSnapshotStore snapshotStore;

    private volatile MBeanServer privateServer;
    private volatile ObjectName controlObjectName;

    public PrivateJmxTracingPolicySnapshotClient(
            PrivateControlLocator locator,
            PrivateControlOpenTypeCodec codec,
            InProcessAcceptedSnapshotStore snapshotStore) {
        this.locator = locator;
        this.codec = codec;
        this.snapshotStore = snapshotStore;
    }

    public void initialize() {
        try {
            this.privateServer = locator.locate();
            this.controlObjectName = ObjectName.getInstance(PRIVATE_CONTROL_OBJECT_NAME);
            log.info("PrivateJmxTracingPolicySnapshotClient initialized: ObjectName={}",
                    PRIVATE_CONTROL_OBJECT_NAME);
        } catch (Exception e) {
            throw new TracingConfigurationGuardException(
                    "Cannot initialize snapshot client: private MBeanServer not available. "
                    + e.getMessage());
        }
    }

    // ── TracingPolicySnapshotClient ────────────────────────────────────────────

    @Override
    public TracingPolicyDeliveryResult applySnapshot(
            long expectedSequence,
            CompleteTracingPolicySnapshot snapshot,
            TracingPolicySource source) {

        MBeanServer server = this.privateServer;
        ObjectName name   = this.controlObjectName;
        if (server == null || name == null) {
            log.warn("applySnapshot called before initialization; source={}", source);
            return TracingPolicyDeliveryResult.FAILED;
        }

        Object encoded;
        try {
            encoded = codec.encode(snapshot);
        } catch (Exception e) {
            throw new TracingPolicyContractViolationException(
                    "Codec failed to encode snapshot — programming defect. source=" + source, e);
        }

        TracingPolicyDeliveryResult outcome;
        try {
            Object raw = server.invoke(
                    name,
                    "applySnapshot",
                    new Object[]{expectedSequence, encoded, source.name()},
                    new String[]{long.class.getName(),
                                 "javax.management.openmbean.CompositeData",
                                 String.class.getName()}
            );
            outcome = parseOutcome(raw);   // throws ContractViolation on unknown string/type
        } catch (TracingPolicyContractViolationException e) {
            throw e;  // re-propagate fail-fast
        } catch (MBeanException e) {
            // Agent threw a checked exception: extract and classify cause
            Throwable cause = e.getTargetException();
            if (cause instanceof IllegalArgumentException || cause instanceof IllegalStateException) {
                throw new TracingPolicyContractViolationException(
                        "Agent rejected applySnapshot as a contract violation. source=" + source, cause);
            }
            log.error("applySnapshot MBeanException (transport): source={} cause={}", source, cause.getMessage());
            return TracingPolicyDeliveryResult.FAILED;
        } catch (RuntimeMBeanException e) {
            RuntimeException cause = e.getTargetException();
            if (cause instanceof IllegalArgumentException || cause instanceof IllegalStateException
                    || cause instanceof TracingPolicyContractViolationException) {
                throw new TracingPolicyContractViolationException(
                        "Agent RuntimeMBeanException — contract defect. source=" + source, cause);
            }
            log.error("applySnapshot RuntimeMBeanException (transport): source={} cause={}", source, cause.getMessage());
            return TracingPolicyDeliveryResult.FAILED;
        } catch (ReflectionException e) {
            // Reflection error = method not found or signature mismatch = wire schema mismatch
            throw new TracingPolicyContractViolationException(
                    "applySnapshot ReflectionException — JMX method not found or signature mismatch. " +
                    "Check Agent/App version compatibility. source=" + source, e);
        } catch (Exception e) {
            log.error("applySnapshot JMX transport error: source={} error={}", source, e.getMessage(), e);
            return TracingPolicyDeliveryResult.FAILED;
        }

        // Update in-process store on successful delivery (no new JMX reads)
        if (outcome == TracingPolicyDeliveryResult.APPLIED
                || outcome == TracingPolicyDeliveryResult.UNCHANGED) {
            snapshotStore.update(new AcceptedSnapshotWithSequence(expectedSequence + 1, snapshot));
        }
        return outcome;
    }

    // ── AcceptedTracingPolicyReader (secret-safe, for Actuator read endpoint) ──

    @Override
    public java.util.Optional<AcceptedPolicyProjection> currentProjection() {
        MBeanServer server = this.privateServer;
        ObjectName name   = this.controlObjectName;
        if (server == null || name == null) return java.util.Optional.empty();
        try {
            Object result = server.invoke(name, "readAcceptedProjection", null, null);
            if (result == null) return java.util.Optional.empty();
            return java.util.Optional.of(codec.decodeProjection(result));
        } catch (MBeanException | RuntimeMBeanException | ReflectionException e) {
            Throwable cause = unwrapJmx(e);
            if (cause instanceof TracingPolicyContractViolationException cv) throw cv;
            log.debug("readAcceptedProjection JMX error: {}", cause.getMessage());
            return java.util.Optional.empty();
        } catch (Exception e) {
            log.debug("readAcceptedProjection failed: {}", e.getMessage());
            return java.util.Optional.empty();
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private TracingPolicyDeliveryResult parseOutcome(Object raw) {
        if (raw instanceof String s) {
            try {
                return TracingPolicyDeliveryResult.valueOf(s);
            } catch (IllegalArgumentException ex) {
                throw new TracingPolicyContractViolationException(
                        "Agent returned unknown applySnapshot outcome: '" + s +
                        "'. Indicates Agent/App version mismatch.");
            }
        }
        throw new TracingPolicyContractViolationException(
                "Agent returned unexpected applySnapshot result type: " +
                (raw == null ? "null" : raw.getClass().getName()));
    }

    private static Throwable unwrapJmx(Exception e) {
        if (e instanceof MBeanException mb && mb.getTargetException() != null)
            return mb.getTargetException();
        if (e instanceof RuntimeMBeanException rb && rb.getTargetException() != null)
            return rb.getTargetException();
        if (e instanceof ReflectionException re && re.getTargetException() != null)
            return re.getTargetException();
        return e;
    }
}
