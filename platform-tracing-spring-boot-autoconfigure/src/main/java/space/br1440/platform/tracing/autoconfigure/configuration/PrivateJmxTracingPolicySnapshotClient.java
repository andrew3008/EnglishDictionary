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
import javax.management.openmbean.CompositeData;
import java.util.Optional;
import java.util.UUID;

/**
 * Private JMX client implementing three capability interfaces.
 * <p>
 * E7/P0 wire contract:
 * <pre>
 *   6 marker reads:             PRIV-R01..R06 (unchanged)
 *   1 complete-snapshot read:   readAcceptedComplete  (PROPOSED_PRIV_R07, implemented in 00b987a;
 *                                                       status PENDING_COMMITTEE_APPROVAL — see
 *                                                       PrivateControlWriter and
 *                                                       docs/research/evidence/priv-r07-proposal/)
 *   1 mutation:                 applySnapshot         (PRIV-W01, unchanged)
 * </pre>
 * <p>
 * runtimeInstanceId contract:
 *   The runtimeInstanceId used in toProjection() is decoded from the Agent
 *   wire response (AcceptedSnapshotWithSequence.runtimeInstanceId()), NOT
 *   from the locator-cached value. This ensures restart detection is based
 *   on the same instance that owns the accepted snapshot, not the instance
 *   that was current at initialization time.
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

    private static final Object[] NO_ARGS = new Object[0];
    private static final String[] NO_SIG  = new String[0];

    private final PrivateControlLocator locator;
    private final PrivateControlOpenTypeCodec codec;

    private volatile MBeanServer privateServer;
    private volatile ObjectName  controlObjectName;

    /**
     * Runtime identity of the Agent instance that produced the most recent
     * {@code readAcceptedComplete} snapshot. Populated exclusively from the wire response
     * inside {@link #currentComplete()} — never from locator-cache, never application-generated.
     * Used as {@code expectedRuntimeInstanceId} on the immediately following
     * {@link #applySnapshot} call so that an Agent restart between read and apply is detected
     * by the Agent (RUNTIME_INSTANCE_MISMATCH) instead of relying on sequence collision alone.
     */
    private volatile String lastKnownRuntimeInstanceId;

    public PrivateJmxTracingPolicySnapshotClient(
            PrivateControlLocator locator,
            PrivateControlOpenTypeCodec codec) {
        this.locator = locator;
        this.codec   = codec;
    }

    public void initialize() {
        try {
            PrivateControlLocator.LocatedPrivateControl located = locator.locate();
            this.privateServer     = located.server();
            this.controlObjectName = located.writerObjectName();
            log.info("PrivateJmxTracingPolicySnapshotClient initialized: {}",
                    controlObjectName);
        } catch (Exception e) {
            throw new TracingConfigurationGuardException(
                    "Cannot initialize snapshot client: private MBeanServer unavailable. " + e.getMessage());
        }
    }

    // ── TracingPolicySnapshotClient ───────────────────────────────────────────

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

        String runtimeInstanceId = this.lastKnownRuntimeInstanceId;
        if (runtimeInstanceId == null) {
            throw new TracingConfigurationGuardException(
                    "applySnapshot called before any successful currentComplete() read; "
                    + "expectedRuntimeInstanceId is unknown. source=" + source);
        }

        CompositeData encoded;
        try {
            encoded = codec.encodeApplyRequest(
                    runtimeInstanceId,
                    expectedSequence,
                    UUID.randomUUID().toString(),
                    sourceAuthorityFor(source),
                    applyTriggerFor(source),
                    "",
                    snapshot);
        } catch (Exception e) {
            throw new TracingPolicyContractViolationException(
                    "Codec failed to encode snapshot — programming defect. source=" + source, e);
        }

        try {
            // Exactly one CompositeData argument — matches the Agent applySnapshot
            // MBeanOperationInfo (single "request" parameter). Do NOT add expectedSequence
            // or source as separate positional arguments; both are already encoded inside
            // the request composite.
            Object raw = server.invoke(
                    name, "applySnapshot",
                    new Object[]{encoded},
                    new String[]{"javax.management.openmbean.CompositeData"});
            return parseOutcome(raw);
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
    }

    // ── AcceptedTracingPolicySnapshotReader (PRIV-R07) ────────────────────────

    @Override
    public Optional<AcceptedSnapshotWithSequence> currentComplete() {
        MBeanServer server = this.privateServer;
        ObjectName  name   = this.controlObjectName;
        if (server == null || name == null) return Optional.empty();
        try {
            Object result = server.invoke(name, "readAcceptedComplete", NO_ARGS, NO_SIG);
            if (result == null) return Optional.empty();
            AcceptedSnapshotWithSequence decoded = codec.decodeCompleteWithSequence(result);
            this.lastKnownRuntimeInstanceId = decoded.runtimeInstanceId();
            return Optional.of(decoded);
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
                    "readAcceptedComplete not found on Agent MBean — Agent/App version mismatch.", e);
        } catch (Exception e) {
            log.debug("readAcceptedComplete failed (transport): {}", e.getMessage());
            return Optional.empty();
        }
    }

    // ── AcceptedTracingPolicyReader ────────────────────────────────────────────

    @Override
    public Optional<AcceptedPolicyProjection> currentProjection() {
        return currentComplete().map(this::toProjection);
    }

    private AcceptedPolicyProjection toProjection(AcceptedSnapshotWithSequence accepted) {
        CompleteTracingPolicySnapshot s = accepted.snapshot();
        // runtimeInstanceId is taken from the wire response, not from the locator cache.
        // This is correct: the snapshot is authoritative for which Agent instance owns it.
        AgentReadinessView readiness = new AgentReadinessView(
                accepted.runtimeInstanceId(),
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
        return new AcceptedPolicyProjection(readiness, leaves, TracingApplyDiagnostics.absent());
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /**
     * The Agent {@code applySnapshot} response is a {@code CompositeData} typed
     * {@code ApplySnapshotResponse.v1} (see {@code PrivateControlWireTypes.RESPONSE}) — never
     * a bare {@code String}. {@code outcome} is one field inside it.
     */
    private TracingPolicyDeliveryResult parseOutcome(Object raw) {
        if (raw instanceof CompositeData) {
            PrivateControlOpenTypeCodec.PrivateControlResponse response = codec.decodeResponse((CompositeData) raw);
            try {
                return TracingPolicyDeliveryResult.valueOf(response.outcome());
            } catch (IllegalArgumentException ex) {
                throw new TracingPolicyContractViolationException(
                        "Unknown applySnapshot outcome: '" + response.outcome() + "' — Agent/App version mismatch");
            }
        }
        throw new TracingPolicyContractViolationException(
                "applySnapshot returned unexpected type: " +
                (raw == null ? "null" : raw.getClass().getName()));
    }

    /**
     * Maps the Spring-side {@link TracingPolicySource} to the Agent {@code PolicySourceAuthority}
     * token name. NOT a plain {@code .name()} pass-through: Agent has no {@code ACTUATOR} value —
     * the Actuator adapter is Agent-side {@code DEV_ACTUATOR}. Agent enum:
     * {@code HELM, CONFIG_SERVER, DEV_ACTUATOR}.
     */
    private static String sourceAuthorityFor(TracingPolicySource source) {
        return switch (source) {
            case CONFIG_SERVER -> "CONFIG_SERVER";
            case ACTUATOR -> "DEV_ACTUATOR";
        };
    }

    /**
     * Maps the Spring-side {@link TracingPolicySource} to the Agent {@code PolicyApplyTrigger}
     * token name. Agent enum: {@code INITIAL, REFRESH, DEVELOPER_COMMAND}.
     */
    private static String applyTriggerFor(TracingPolicySource source) {
        return switch (source) {
            case CONFIG_SERVER -> "REFRESH";
            case ACTUATOR -> "DEVELOPER_COMMAND";
        };
    }

    private static boolean isContractCause(Throwable t) {
        return t instanceof IllegalArgumentException
            || t instanceof IllegalStateException
            || t instanceof TracingPolicyContractViolationException;
    }
}
