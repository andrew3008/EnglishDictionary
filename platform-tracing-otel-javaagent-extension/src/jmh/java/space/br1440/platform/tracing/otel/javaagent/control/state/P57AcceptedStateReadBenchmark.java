package space.br1440.platform.tracing.otel.javaagent.control.state;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * P5.7 budget benchmark — hot-path read of accepted state.
 *
 * <p>All five methods perform a single {@code AcceptedPolicyAccess.current()} call
 * which resolves to one {@code AtomicReference.get()} (volatile load). The result
 * is consumed by {@link Blackhole} to prevent dead-code elimination.
 *
 * <p>Budget constraint: {@code gc.alloc.rate.norm = 0 B/op} — the volatile load
 * must not allocate on the hot path.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(value = 3, warmups = 2)
@Warmup(iterations = 3, time = 5)
@Measurement(iterations = 5, time = 5)
public class P57AcceptedStateReadBenchmark {

    /** Baseline: raw reference load only. */
    @Benchmark
    public void readCurrentReference(AcceptedStateBenchmarkFixtures fixtures, Blackhole bh) {
        bh.consume(fixtures.accessNoRoutes.current());
    }

    /** Leaf boolean read: samplingEnabled. */
    @Benchmark
    public void readSamplingEnabledLeaf(AcceptedStateBenchmarkFixtures fixtures, Blackhole bh) {
        bh.consume(fixtures.accessNoRoutes.current().samplingEnabled());
    }

    /** Full projection read — no routes. */
    @Benchmark
    public void readFullProjectionNoRoutes(AcceptedStateBenchmarkFixtures fixtures, Blackhole bh) {
        AcceptedPolicyView view = fixtures.accessNoRoutes.current();
        bh.consume(view.samplingEnabled());
        bh.consume(view.samplingRatio());
        bh.consume(view.validationEnabled());
        bh.consume(view.validationStrict());
        bh.consume(view.exporterEnabled());
        bh.consume(view.traceControlHeadersEnabled());
        bh.consume(view.diagnosticsLogLevel());
        bh.consume(view.runtimeInstanceId());
        bh.consume(view.acceptedSequence());
    }

    /** Full projection read — five routes. */
    @Benchmark
    public void readFullProjectionFiveRoutes(AcceptedStateBenchmarkFixtures fixtures, Blackhole bh) {
        AcceptedPolicyView view = fixtures.accessFiveRoutes.current();
        bh.consume(view.samplingEnabled());
        bh.consume(view.samplingRatio());
        bh.consume(view.samplingRouteRatios());
        bh.consume(view.validationEnabled());
        bh.consume(view.validationStrict());
        bh.consume(view.exporterEnabled());
        bh.consume(view.traceControlHeadersEnabled());
        bh.consume(view.diagnosticsLogLevel());
        bh.consume(view.runtimeInstanceId());
        bh.consume(view.acceptedSequence());
    }
}
