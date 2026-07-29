package space.br1440.platform.tracing.bench.acceptedstate;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import space.br1440.platform.tracing.otel.javaagent.control.state.EffectiveTracingPolicyDigestAccess;

import java.util.concurrent.TimeUnit;

/**
 * P5.7 §20 — SHA-256 digest throughput for {@code EffectiveTracingPolicyDigest.sha256()}.
 *
 * <p>{@code sha256()} is called on the apply path (Agent side) when building a
 * {@link space.br1440.platform.tracing.otel.javaagent.control.state.PolicyApplyResult}. It
 * serialises all eight Category-A leaves into a deterministic byte stream and hashes them.
 * The cost is dominated by {@code MessageDigest.getInstance("SHA-256")} (not pooled) and the
 * {@code DataOutputStream} write loop.
 *
 * <p><b>P5.7 budget contract (AverageTime, JDK 21, windows-i5-13500):</b>
 * <ul>
 *   <li>{@code digestNoRoutes}   ≤ 800 ns/op</li>
 *   <li>{@code digestFiveRoutes} ≤ 1 200 ns/op</li>
 * </ul>
 *
 * <p><b>Formal run parameters:</b><br>
 * {@code @Fork(value=3, warmups=2)},
 * {@code @Warmup(iterations=3, time=5s)},
 * {@code @Measurement(iterations=5, time=5s)}
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(value = 3, warmups = 2)
@Warmup(iterations = 3, time = 5, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 5, timeUnit = TimeUnit.SECONDS)
public class P57AcceptedStateDigestBenchmark extends AcceptedStateBenchmarkFixtures {

    /**
     * SHA-256 of an 8-leaf policy with zero route-ratio entries.
     * Logical payload ≈ 168 bytes (domain separator + 8 typed fields, no route entries).
     *
     * <p>Budget: ≤ 800 ns/op.
     */
    @Benchmark
    public String digestNoRoutes() {
        return EffectiveTracingPolicyDigestAccess.sha256(viewNoRoutes);
    }

    /**
     * SHA-256 of an 8-leaf policy with five route-ratio entries.
     * Each route contributes 4 (prefix-length) + prefix bytes + 8 (double bits) to the stream.
     * Logical payload ≈ 368 bytes for the five routes used in this fixture.
     *
     * <p>Budget: ≤ 1 200 ns/op.
     */
    @Benchmark
    public String digestFiveRoutes() {
        return EffectiveTracingPolicyDigestAccess.sha256(viewFiveRoutes);
    }
}
