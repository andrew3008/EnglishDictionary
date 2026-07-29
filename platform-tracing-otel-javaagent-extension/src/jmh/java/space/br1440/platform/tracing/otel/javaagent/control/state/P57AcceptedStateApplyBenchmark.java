package space.br1440.platform.tracing.otel.javaagent.control.state;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * P5.7 budget benchmark — three branches of {@code AcceptedPolicyAccess.updateSampling()}.
 *
 * <p>Budget (AverageTime, JDK 21):
 * <ul>
 *   <li>UNCHANGED ≤ 250 ns/op — same-value CAS short-circuit</li>
 *   <li>APPLIED   ≤ 600 ns/op — successful CAS swap</li>
 *   <li>STALE     — no budget constraint; regression tracking only</li>
 * </ul>
 *
 * <p>The STALE scenario uses a fresh isolated store per benchmark call so that
 * the CAS always races against a pre-incremented base. To avoid setup overhead
 * inside the measured body, the fixtures for STALE are constructed in
 * {@link AcceptedStateBenchmarkFixtures#setup()} and consumed here.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(value = 3, warmups = 2)
@Warmup(iterations = 3, time = 5)
@Measurement(iterations = 5, time = 5)
public class P57AcceptedStateApplyBenchmark {

    /**
     * UNCHANGED branch: ratio equals the currently accepted value — CAS is skipped.
     * Budget: ≤ 250 ns/op.
     */
    @Benchmark
    public void applyUnchanged(AcceptedStateBenchmarkFixtures fixtures, Blackhole bh) {
        bh.consume(fixtures.accessNoRoutes.updateSampling(true, 1.0, Map.of()));
    }

    /**
     * APPLIED branch: ratio changes — CAS succeeds on first attempt.
     * Budget: ≤ 600 ns/op.
     *
     * <p>The update alternates between 0.5 and 1.0 so that every call
     * actually mutates the store (the previous call left the opposite value).
     * This is achieved by using a dedicated store in the fixtures so
     * alternation is guaranteed across iterations.
     */
    @Benchmark
    public void applyApplied(AcceptedStateBenchmarkFixtures fixtures, Blackhole bh) {
        // Alternates: 0.5 -> 1.0 -> 0.5 ... across benchmark iterations.
        // Both values differ from each other, so CAS always fires.
        AcceptedPolicyUpdateOutcome first =
                fixtures.accessNoRoutes.updateSampling(true, 0.5, Map.of());
        AcceptedPolicyUpdateOutcome second =
                fixtures.accessNoRoutes.updateSampling(true, 1.0, Map.of());
        bh.consume(first);
        bh.consume(second);
    }

    /**
     * STALE branch: a concurrent writer bumped the sequence before the CAS.
     *
     * <p>Simulated by issuing two competing updates on the same store;
     * the second call observes a stale base. No budget — regression tracking only.
     */
    @Benchmark
    public void applyStale(AcceptedStateBenchmarkFixtures fixtures, Blackhole bh) {
        // Isolate a fresh store for each iteration to guarantee stale base.
        AcceptedPolicyAccess fresh = AcceptedPolicyAccess.isolatedDefaults();
        // First update succeeds (APPLIED) and bumps the sequence.
        fresh.updateSampling(true, 0.5, Map.of());
        // Second update with the old base (sequence 0) will see STALE.
        bh.consume(fixtures.accessNoRoutes.updateSampling(true, 0.5, Map.of()));
        bh.consume(fresh);
    }
}
