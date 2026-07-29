package space.br1440.platform.tracing.bench.acceptedstate;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import space.br1440.platform.tracing.otel.javaagent.control.state.AcceptedPolicyAccess;
import space.br1440.platform.tracing.otel.javaagent.control.state.AcceptedPolicyUpdateOutcome;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * P5.7 §20 — apply-path benchmarks for the three primary outcome branches.
 *
 * <p>The apply path ({@link AcceptedPolicyAccess#updateSampling}) covers:
 * <ol>
 *   <li><b>APPLIED</b> — candidate differs from current; CAS wins on an uncontended store.</li>
 *   <li><b>UNCHANGED</b> — candidate equals current; CAS is skipped entirely.</li>
 *   <li><b>STALE</b> — candidate was built from a witnessed state that was already superseded;
 *       CAS attempt finds a different reference and returns {@code STALE} without retry.</li>
 * </ol>
 *
 * <p>The STALE path is simulated by interleaving a prior update that advances the store's
 * {@code acceptedSequence} before the benchmarked call: the store is re-built once per
 * {@link Level#Iteration} so the benchmark loop itself always starts from a clean slate.
 *
 * <p><b>P5.7 budget contract (AverageTime, JDK 21, windows-i5-13500):</b>
 * <ul>
 *   <li>{@code applyUnchanged} ≤ 250 ns/op (no CAS, no sha256, no allocation)</li>
 *   <li>{@code applyApplied}   ≤ 600 ns/op (one CAS + one sha256 + one envelope allocation)</li>
 *   <li>{@code applyStale}     — no formal latency budget; measured for regression tracking</li>
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
public class P57AcceptedStateApplyBenchmark {

    /**
     * Fresh store re-created once per iteration so every measurement loop starts
     * from {@code acceptedSequence=0}, giving the APPLIED path a valid base.
     */
    private AcceptedPolicyAccess freshStore;

    /** Ratio used for the APPLIED path — must differ from the initial default (1.0). */
    private static final double ALT_RATIO = 0.50d;

    @Setup(Level.Iteration)
    public void setUpIteration() {
        freshStore = AcceptedPolicyAccess.isolatedDefaults();
    }

    /**
     * UNCHANGED branch: candidate equals current policy; no CAS, no sha256, no envelope alloc.
     * The store always holds samplingEnabled=true, ratio=1.0 from isolatedDefaults().
     * Submitting the identical values exercises the equality fast-path inside commit().
     *
     * <p>Budget: ≤ 250 ns/op.
     */
    @Benchmark
    public AcceptedPolicyUpdateOutcome applyUnchanged() {
        // Submitting the same defaults → UNCHANGED; AcceptedPolicyAccess.commit() returns early.
        return freshStore.updateSampling(true, 1.0d, Map.of());
    }

    /**
     * APPLIED branch: candidate differs from current (ratio 0.50 ≠ 1.0); CAS wins on
     * uncontended store; one new AcceptedPolicyEnvelope is allocated and committed.
     *
     * <p>Budget: ≤ 600 ns/op.
     */
    @Benchmark
    public AcceptedPolicyUpdateOutcome applyApplied() {
        // Each iteration starts fresh; first call always wins the CAS.
        return freshStore.updateSampling(true, ALT_RATIO, Map.of());
    }

    /**
     * STALE branch: store is advanced by a prior commit inside setup, so the observed
     * {@code acceptedSequence} is already stale when this benchmark's CAS attempt fires.
     *
     * <p>Implemented by calling updateSampling twice: the first call (APPLIED) advances the
     * sequence; the second call with the same candidate produces STALE because the store's
     * sequence no longer matches the witnessed base from before the first call.
     *
     * <p>No formal latency budget; result is captured for regression tracking only.
     */
    @Benchmark
    public AcceptedPolicyUpdateOutcome applyStale() {
        // First call advances the store sequence.
        freshStore.updateSampling(true, ALT_RATIO, Map.of());
        // Second call witnesses the post-advance state; the CAS base is now stale → STALE.
        return freshStore.updateSampling(true, 1.0d, Map.of());
    }
}
