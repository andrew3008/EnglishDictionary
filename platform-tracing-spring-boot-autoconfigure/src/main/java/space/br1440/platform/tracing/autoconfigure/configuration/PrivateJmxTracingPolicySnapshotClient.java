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
 *   6 marker reads          — unchanged (PRIV-R01..R06)
 *   1 complete-snapshot read: readAcceptedComplete  (PRIV-R07, approved Variant A)
 *   1 mutation:             applySnapshot           (PRIV-W01, unchanged)
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
 * <p>
 * NOTE: Agent-side AggregatePolicyControlMBean.readAcceptedComplete() has not yet
 * been implemented. Until that Agent PR is merged and approved, any call to
 * {@link #currentComplete()} will produce a {@code ReflectionException} which is
 * classified as a contract violation and thrown as
 * {@link TracingPolicyContractViolationException}. P4 remains blocked.
 */
public final class PrivateJmxTracingPolicySnapshotClient
        implements TracingPolicySnapshotClient,
                   AcceptedTracingPolicySnapshotReader,
                   AcceptedTracingPolicyReader {

    private static final Logger log = LoggerFactory.getLogger(PrivateJmxTracingPolicySnapshotClient.class);

    private static final Object[] NO_ARGS = new Object[0];
    private static final String[] NO_SIG  = new String[0];

    private final PrivateControlLocator locator;
    private final PrivateControlOpenTypeCodec codec;

    private volatile MBeanServer privateServer;
    private volatile ObjectName  controlObjectName;
    private volatile String      runtimeInstanceId;

    public PrivateJmxTracingPolicySnapshotClient(
            PrivateControlLocator locator,
            PrivateControlOpenTypeCodec codec) {
        this.locator = locator;
        this.codec   = codec;
    }

    public void initialize() {
        try {
            PrivateControlLocator.LocatedPrivateControl located = locator.locate();
            this.privateServer    = located.server();
            this.controlObjectName = located.writerObjectName();
            this.runtimeInstanceId = located.runtimeInstanceId();
            log.info("PrivateJmxTracingPolicySnapshotClient initialized: {} runtimeInstanceId={}",
                    controlObjectName, runtimeInstanceId);
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

    // ── AcceptedTracingPolicySnapshotReader (PRIV-R07, Variant A) ─────────────

    /**
     * Reads the complete accepted snapshot from the Agent via PRIV-R07
     * {@code readAcceptedComplete}.
     * <p>
     * IMPORTANT: This call will throw {@link TracingPolicyContractViolationException}
     * (wrapping {@link ReflectionException}) until the Agent MBean exposes
     * {@code readAcceptedComplete}. P4 remains blocked until that Agent PR is merged.
     */
    @Override
    public Optional<AcceptedSnapshotWithSequence> currentComplete() {
        MBeanServer server = this.privateServer;
        ObjectName  name   = this.controlObjectName;
        if (server == null || name == null) return Optional.empty();
        try {
            // Use empty arrays — NOT null — for zero-argument operations
            Object result = server.invoke(name, "readAcceptedComplete", NO_ARGS, NO_SIG);
            if (result == null) return Optional.empty();
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
            // readAcceptedComplete not present on Agent MBean — version mismatch.
            // This is the expected failure mode until Agent-side PR is merged.
            throw new TracingPolicyContractViolationException(
                    "readAcceptedComplete not found on Agent MBean — Agent-side implementation "
                    + "required (PRIV-R07). P4 is blocked until Agent PR is merged.", e);
        } catch (Exception e) {
            log.debug("readAcceptedComplete failed (transport): {}", e.getMessage());
            return Optional.empty();
        }
    }

    // ── AcceptedTracingPolicyReader (secret-safe, public Actuator endpoint) ───

    @Override
    public Optional<AcceptedPolicyProjection> currentProjection() {
        return currentComplete().map(accepted -> toProjection(accepted, this.runtimeInstanceId));
    }

    private static AcceptedPolicyProjection toProjection(
            AcceptedSnapshotWithSequence accepted,
            String resolvedRuntimeInstanceId) {

        CompleteTracingPolicySnapshot s = accepted.snapshot();

        // runtimeInstanceId is authoritative: sourced from PrivateMarker at initialize() time.
        // It is NOT fabricated — any blank value here signals a genuine locate() failure
        // that would have thrown at initialization.
        AgentReadinessView readiness = new AgentReadinessView(
                resolvedRuntimeInstanceId != null ? resolvedRuntimeInstanceId : "",
                accepted.acceptedSequence(),
                true
        );
        AcceptedPolicyLeaves leaves = new AcceptedPolicyLeaves(
                s.samplingEnabled(),
                s.samplingRatio(),
                s.samplingRouteRatios().size(),
                s.validationEnabled(),
                s.validationStrict(),
                s.exporterEnabled(),
                s.traceControlHeadersEnabled(),
                s.diagnosticsLogLevel()
        );
        // policyDigest and latestApplyOutcome are NOT part of the readAcceptedComplete
        // wire response (PRIV-R07). They are exposed exclusively by the separate
        // applySnapshot response path (PRIV-W01). Returning absent sentinels here
        // is intentional and contract-correct: callers must not infer these values
        // from the complete-read path.
        TracingApplyDiagnostics diag = TracingApplyDiagnostics.notAvailableViaCompleteRead();
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
