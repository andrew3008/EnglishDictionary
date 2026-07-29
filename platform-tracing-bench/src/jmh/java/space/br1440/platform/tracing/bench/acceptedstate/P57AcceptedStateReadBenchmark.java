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
import org.openjdk.jmh.infra.Blackhole;

import space.br1440.platform.tracing.otel.javaagent.control.state.AcceptedPolicyView;

import java.util.concurrent.TimeUnit;

/**
 * P5.7 §20 — hot-path accepted-state read benchmarks.
 *
 * <p>Measures the cost of {@code AcceptedPolicyAccess.current()} — a single
 * {@link java.util.concurrent.atomic.AtomicReference#get() volatile load} returning an
 * immutable {@link AcceptedPolicyView} — plus the cost of dereferencing individual
 * policy leaves from that view.
 *
 * <p><b>P5.7 budget contract:</b>
 * <ul>
 *   <li>{@code gc.alloc.rate.norm} MUST be {@code 0 B/op} for all methods in this class.</li>
 *   <li>No latency budget is formally gated here; latency is documented by {@link S01AcceptedStateReadBenchmark}.</li>
 * </ul>
 *
 * <p><b>Formal run parameters (CI / baseline capture):</b><br>
 * {@code @Fork(value=3, warmups=2)}<br>
 * {@code @Warmup(iterations=3, time=5, timeUnit=SECONDS)}<br>
 * {@code @Measurement(iterations=5, time=5, timeUnit=SECONDS)}
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(value = 3, warmups = 2)
@Warmup(iterations = 3, time = 5, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 5, timeUnit = TimeUnit.SECONDS)
public class P57AcceptedStateReadBenchmark extends AcceptedStateBenchmarkFixtures {

    /**
     * Baseline: one volatile load of the {@code AtomicReference<AcceptedPolicyEnvelope>}.
     * Dereferencing zero fields ensures the measurement captures only the reference load itself.
     *
     * <p>Budget: {@code gc.alloc.rate.norm = 0 B/op}.
     */
    @Benchmark
    public AcceptedPolicyView currentViewNoRoutes() {
        return accessNoRoutes.current();
    }

    /**
     * Reads {@code samplingEnabled} from the current view (no routes).
     * One volatile load + one final boolean field read.
     *
     * <p>Budget: {@code gc.alloc.rate.norm = 0 B/op}.
     */
    @Benchmark
    public boolean readSamplingEnabledNoRoutes() {
        return accessNoRoutes.current().samplingEnabled();
    }

    /**
     * Reads {@code samplingEnabled} from the five-routes view.
     * Identical cost as no-routes: the route map is not accessed.
     *
     * <p>Budget: {@code gc.alloc.rate.norm = 0 B/op}.
     */
    @Benchmark
    public boolean readSamplingEnabledFiveRoutes() {
        return accessFiveRoutes.current().samplingEnabled();
    }

    /**
     * Full projection: reads {@code acceptedSequence}, {@code samplingRatio}, and
     * {@code samplingRouteRatios().size()} from the same single reference load.
     * Shows that multi-field projection from one atomic reference incurs no extra allocation.
     *
     * <p>Budget: {@code gc.alloc.rate.norm = 0 B/op}.
     */
    @Benchmark
    public void fullProjectionNoRoutes(Blackhole bh) {
        AcceptedPolicyView view = accessNoRoutes.current();
        bh.consume(view.acceptedSequence());
        bh.consume(view.samplingRatio());
        bh.consume(view.samplingRouteRatios().size());
    }

    /**
     * Full projection on a five-route view: same as above but route map is non-empty.
     * Size read traverses no entries ({@code NavigableMap.size()} is O(1) for TreeMap).
     *
     * <p>Budget: {@code gc.alloc.rate.norm = 0 B/op}.
     */
    @Benchmark
    public void fullProjectionFiveRoutes(Blackhole bh) {
        AcceptedPolicyView view = accessFiveRoutes.current();
        bh.consume(view.acceptedSequence());
        bh.consume(view.samplingRatio());
        bh.consume(view.samplingRouteRatios().size());
    }
}
