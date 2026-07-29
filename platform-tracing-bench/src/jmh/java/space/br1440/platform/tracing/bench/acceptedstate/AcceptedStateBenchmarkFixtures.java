package space.br1440.platform.tracing.bench.acceptedstate;

import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import space.br1440.platform.tracing.autoconfigure.jmx.privatecontrol.PrivateControlOpenTypeCodec;
import space.br1440.platform.tracing.otel.javaagent.control.state.AcceptedPolicyAccess;
import space.br1440.platform.tracing.otel.javaagent.control.state.AcceptedPolicyView;

import javax.management.openmbean.CompositeData;
import java.util.Map;
import java.util.TreeMap;

/**
 * Shared benchmark fixtures for P5.7 accepted-state JMH benchmarks.
 *
 * <p>All expensive construction (store creation, CompositeData assembly, codec instantiation)
 * is performed once per trial in {@link #setUp()}. Benchmark loops receive pre-built
 * immutable objects and incur no setup allocation on the hot path.
 *
 * <p>Two route-ratio profiles are provided:
 * <ul>
 *   <li>{@code noRoutes}  – zero per-route entries (minimal payload, ~168 bytes logical).</li>
 *   <li>{@code fiveRoutes} – five route entries (typical production shape, ~368 bytes logical).</li>
 * </ul>
 *
 * <p><b>P5.7 budget contract (informational, enforced by CI grep):</b><br>
 * BUDGET:read:0 B/op gc.alloc.rate.norm<br>
 * BUDGET:digest_no_routes:800 ns/op AverageTime JDK21<br>
 * BUDGET:digest_five_routes:1200 ns/op AverageTime JDK21<br>
 * BUDGET:apply_unchanged:250 ns/op AverageTime JDK21<br>
 * BUDGET:apply_applied:600 ns/op AverageTime JDK21<br>
 * BUDGET:encode_no_routes:15000 ns/op AverageTime JDK21<br>
 * BUDGET:encode_five_routes:20000 ns/op AverageTime JDK21<br>
 * BUDGET:decode_no_routes:20000 ns/op AverageTime JDK21<br>
 * BUDGET:decode_five_routes:25000 ns/op AverageTime JDK21
 */
@State(Scope.Benchmark)
public class AcceptedStateBenchmarkFixtures {

    // ── production store (AcceptedPolicyAccess wraps AcceptedPolicyStateStore) ─────

    /** Uncontended, defaults-only store: samplingEnabled=true, ratio=1.0, no routes. */
    public AcceptedPolicyAccess accessNoRoutes;

    /** Uncontended store pre-loaded with five route-ratio entries. */
    public AcceptedPolicyAccess accessFiveRoutes;

    // ── Agent-side codec ──────────────────────────────────────────────────────────

    /**
     * Pre-encoded {@code CompositeData} produced by
     * {@link space.br1440.platform.tracing.otel.javaagent.control.state.PrivateControlOpenTypeCodec
     * Agent PrivateControlOpenTypeCodec#encodeAcceptedComplete(AcceptedPolicyView)}
     * for use in decode benchmarks.
     *
     * <p>The encode path is benchmarked separately in
     * {@link P57AcceptedStateCodecBenchmark}; here the payload is frozen at trial
     * setup so the decode loop receives a stable, non-varying input.
     */
    public CompositeData encodedNoRoutes;
    public CompositeData encodedFiveRoutes;

    // ── Spring-side codec ─────────────────────────────────────────────────────────

    /** Stateless Spring-side codec; safe to share across threads. */
    public PrivateControlOpenTypeCodec springCodec;

    // ── current views (frozen for read/digest benchmarks) ──────────────────────────

    public AcceptedPolicyView viewNoRoutes;
    public AcceptedPolicyView viewFiveRoutes;

    @Setup(Level.Trial)
    public void setUp() {
        // ── stores ────────────────────────────────────────────────────────────────
        accessNoRoutes = AcceptedPolicyAccess.isolatedDefaults();

        accessFiveRoutes = AcceptedPolicyAccess.isolatedSampling(
                true,
                java.util.List.of(),
                java.util.List.of(),
                fiveRouteRatios(),
                0.75d);

        // ── frozen views ─────────────────────────────────────────────────────────
        viewNoRoutes = accessNoRoutes.current();
        viewFiveRoutes = accessFiveRoutes.current();

        // ── Agent codec ──────────────────────────────────────────────────────────
        // PrivateControlOpenTypeCodec is package-private in the Agent module.
        // We reach it via the test-white-box export declared in the bench build.
        space.br1440.platform.tracing.otel.javaagent.control.state.PrivateControlOpenTypeCodec agentCodec =
                new space.br1440.platform.tracing.otel.javaagent.control.state.PrivateControlOpenTypeCodec();
        encodedNoRoutes  = agentCodec.encodeAcceptedComplete(viewNoRoutes);
        encodedFiveRoutes = agentCodec.encodeAcceptedComplete(viewFiveRoutes);

        // ── Spring codec ─────────────────────────────────────────────────────────
        springCodec = new PrivateControlOpenTypeCodec();
    }

    // ── helpers ───────────────────────────────────────────────────────────────────

    private static Map<String, Double> fiveRouteRatios() {
        Map<String, Double> routes = new TreeMap<>();
        routes.put("/api/v1/orders",    0.10d);
        routes.put("/api/v1/payments",  0.25d);
        routes.put("/api/v1/products",  0.50d);
        routes.put("/api/v2/accounts",  0.05d);
        routes.put("/internal/health",  0.00d);
        return routes;
    }
}
