package space.br1440.platform.tracing.otel.javaagent.control.state;

import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.List;
import java.util.Map;

/**
 * Shared JMH fixture for P5.7 accepted-state budget benchmarks.
 *
 * <p>All expensive setup (store creation, CompositeData pre-encoding) is done
 * inside {@link #setup()} which runs at {@link Level#Trial} — outside any
 * measured iteration. The benchmark methods only touch already-warm objects.
 *
 * <p>Two fixture variants are exposed:
 * <ul>
 *   <li>{@link #accessNoRoutes} — default policy, zero per-route ratios</li>
 *   <li>{@link #accessFiveRoutes} — five per-route sampling ratios</li>
 * </ul>
 *
 * <!-- BUDGET:read:0 B/op gc.alloc.rate.norm -->
 * <!-- BUDGET:digest_no_routes:800 ns/op AverageTime JDK21 -->
 * <!-- BUDGET:digest_five_routes:1200 ns/op AverageTime JDK21 -->
 * <!-- BUDGET:apply_unchanged:250 ns/op AverageTime JDK21 -->
 * <!-- BUDGET:apply_applied:600 ns/op AverageTime JDK21 -->
 * <!-- BUDGET:encode_no_routes:15000 ns/op AverageTime JDK21 -->
 * <!-- BUDGET:encode_five_routes:20000 ns/op AverageTime JDK21 -->
 * <!-- BUDGET:decode_no_routes:20000 ns/op AverageTime JDK21 -->
 * <!-- BUDGET:decode_five_routes:25000 ns/op AverageTime JDK21 -->
 */
@State(Scope.Benchmark)
public class AcceptedStateBenchmarkFixtures {

    public AcceptedPolicyAccess accessNoRoutes;
    public AcceptedPolicyAccess accessFiveRoutes;
    public PrivateControlOpenTypeCodecAccess codecAccess;

    @Setup(Level.Trial)
    public void setup() {
        accessNoRoutes = AcceptedPolicyAccess.isolatedSampling(
                true,
                List.of(),
                List.of(),
                Map.of(),
                1.0);

        accessFiveRoutes = AcceptedPolicyAccess.isolatedSampling(
                true,
                List.of(),
                List.of(),
                Map.of(
                        "/api/v1/orders", 0.5,
                        "/api/v1/products", 0.25,
                        "/api/v1/users", 0.75,
                        "/api/v1/payments", 1.0,
                        "/api/v1/health", 0.0),
                0.1);

        codecAccess = new PrivateControlOpenTypeCodecAccess();
    }
}
