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
import java.util.Optional;

/**
 * Private JMX client implementing three capability interfaces.
 * <p>
 * E7/P0 wire contract (after Variant A extension):
 * <pre>
 *   6 marker reads          — unchanged
 *   1 complete-snapshot read: readAcceptedComplete  (NEW, approved Variant A)
 *   1 mutation:             applySnapshot           (unchanged)
 * </pre>
 * readAcceptedProjection is NOT called here — removed from wire.
 * <p>
 * Sequence contract:
 *   APPLIED   → sequence refreshed from Agent via readAcceptedComplete after apply
 *   UNCHANGED → sequence unchanged; no refresh, no arithmetic
 *   STALE     → sequence refreshed then one bounded retry
 *   Application never computes authoritative sequence unilaterally.
 * <p>
 * JMX exception classification:
 *   MBeanException / RuntimeMBeanException with contract cause → fail-fast
 *   ReflectionException (method not found) → fail-fast (version mismatch)
 *   Transport/IO failures → FAILED
 */
public final class PrivateJmxTracingPolicySnapshotClient
        implements TracingPolicySnapshotClient,
                   AcceptedTracingPolicySnapshotReader,
                   AcceptedTracingPolicyReader {

    private static final Logger log = LoggerFactory.getLogger(PrivateJmxTracingPolicySnapshotClient.class);

    private static final String OBJECT_NAME =
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
            this.controlObjectName = ObjectName.getInstance(OBJECT_NAME);
            log.info("PrivateJmxTracingPolicySnapshotClient initialized: {}", OBJECT_NAME);
        } catch (Exception e) {
            throw new TracingConfigurationGuardException(
                    "Cannot initialize snapshot client: private MBeanServer unavailable. " + e.getMessage());
        }
    }

    // ── TracingPolicySnapshotClient ────────────────────────────────────────────

    @Override
    public TracingPolicyDeliveryResult applySnapshot(
            long expectedSequence,
            CompleteTracingPolicySnapshot snapshot,
            TracingPolicySource source) {

        MBeanServer server = this.privateServer;
        ObjectName  name   = this.controlObjectName;
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
                    name, "applySnapshot",
                    new Object[]{expectedSequence, encoded, source.name()},
                    new String[]{long.class.getName(),
                                 "javax.management.openmbean.CompositeData",
                                 String.class.getName()});
            outcome = parseOutcome(raw);
        } catch (TracingPolicyContractViolationException e) {
            throw e;
        } catch (MBeanException e) {
            Throwable cause = e.getTargetException();
            if (isContractCause(cause)) throw new TracingPolicyContractViolationException(
                    "Agent contract violation in applySnapshot. source=" + source, cause);
            log.error("applySnapshot MBeanException (transport): source={}", source, e);
            return TracingPolicyDeliveryResult.FAILED;
        } catch (RuntimeMBeanException e) {
            RuntimeException cause = e.getTargetException();
            if (isContractCause(cause)) throw new TracingPolicyContractViolationException(
                    "Agent RuntimeMBeanException — contract defect. source=" + source, cause);
            log.error("applySnapshot RuntimeMBeanException (transport): source={}", source, e);
            return TracingPolicyDeliveryResult.FAILED;
        } catch (ReflectionException e) {
            throw new TracingPolicyContractViolationException(
                    "applySnapshot ReflectionException — JMX method not found or signature mismatch. "
                    + "Check Agent/App version compatibility. source=" + source, e);
        } catch (Exception e) {
            log.error("applySnapshot transport error: source={}", source, e);
            return TracingPolicyDeliveryResult.FAILED;
        }

        return outcome;
    }

    // ── AcceptedTracingPolicySnapshotReader (E7/P0 Variant A — approved) ──────

    @Override
    public Optional<AcceptedSnapshotWithSequence> currentComplete() {
        MBeanServer server = this.privateServer;
        ObjectName  name   = this.controlObjectName;
        if (server == null || name == null) return Optional.empty();
        try {
            Object result = server.invoke(name, "readAcceptedComplete", null, null);
            if (result == null) return Optional.empty();
            // Contract violation if codec cannot decode: fail-fast
            return Optional.of(codec.decodeCompleteWithSequence(result));
        } catch (TracingPolicyContractViolationException e) {
            throw e;
        } catch (MBeanException e) {
            Throwable cause = e.getTargetException();
            if (isContractCause(cause)) throw new TracingPolicyContractViolationException(
                    "readAcceptedComplete MBeanException — contract defect", cause);
            log.debug("readAcceptedComplete MBeanException (transport): {}", e.getMessage());
            return Optional.empty();
        } catch (RuntimeMBeanException e) {
            RuntimeException cause = e.getTargetException();
            if (isContractCause(cause)) throw new TracingPolicyContractViolationException(
                    "readAcceptedComplete RuntimeMBeanException — contract defect", cause);
            log.debug("readAcceptedComplete RuntimeMBeanException: {}", e.getMessage());
            return Optional.empty();
        } catch (ReflectionException e) {
            throw new TracingPolicyContractViolationException(
                    "readAcceptedComplete not found on Agent MBean — version mismatch", e);
        } catch (Exception e) {
            log.debug("readAcceptedComplete failed (transport): {}", e.getMessage());
            return Optional.empty();
        }
    }

    // ── AcceptedTracingPolicyReader (secret-safe, public Actuator endpoint) ───

    @Override
    public Optional<AcceptedPolicyProjection> currentProjection() {
        // Secret-safe projection is assembled from the complete snapshot locally;
        // no extra JMX operation is invoked. Wire protocol unchanged.
        return currentComplete().map(this::toProjection);
    }

    private AcceptedPolicyProjection toProjection(AcceptedSnapshotWithSequence accepted) {
        CompleteTracingPolicySnapshot s = accepted.snapshot();
        AgentReadinessView readiness = new AgentReadinessView(
                "unknown",                 // runtimeInstanceId: not in complete snapshot; diagnostic only
                accepted.acceptedSequence(),
                true
        );
        AcceptedPolicyLeaves leaves = new AcceptedPolicyLeaves(
                s.samplingEnabled(),
                s.samplingRatio(),
                s.samplingRouteRatios().size(),   // count only, no values
                s.validationEnabled(),
                s.validationStrict(),
                s.exporterEnabled(),
                s.traceControlHeadersEnabled(),
                s.diagnosticsLogLevel()
        );
        TracingApplyDiagnostics diag = new TracingApplyDiagnostics("n/a", "n/a");
        return new AcceptedPolicyProjection(readiness, leaves, diag);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private TracingPolicyDeliveryResult parseOutcome(Object raw) {
        if (raw instanceof String s) {
            try { return TracingPolicyDeliveryResult.valueOf(s); }
            catch (IllegalArgumentException ex) {
                throw new TracingPolicyContractViolationException(
                        "Unknown applySnapshot outcome: '" + s + "' — Agent/App version mismatch");
            }
        }
        throw new TracingPolicyContractViolationException(
                "applySnapshot returned unexpected type: " +
                (raw == null ? "null" : raw.getClass().getName()));
    }

    private static boolean isContractCause(Throwable t) {
        return t instanceof IllegalArgumentException
            || t instanceof IllegalStateException
            || t instanceof TracingPolicyContractViolationException;
    }
}
