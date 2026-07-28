package space.br1440.platform.tracing.bench.acceptedstate;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.concurrent.TimeUnit;

/**
 * S-01 Gate E3 feasibility evidence: allocation profile of the accepted-state hot-path read.
 * <p>
 * The load-bearing metric is {@code gc.alloc.rate.norm} (bytes per operation). The E3 contract
 * requires the hot-path read to be zero-allocation, lock-free, and a single immutable reference load,
 * so anything other than {@code 0 B/op} falsifies the candidate.
 * <p>
 * This is a P0 feasibility proof, not a P5 performance acceptance run: no latency budget is
 * established here.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class S01AcceptedStateReadBenchmark {

    private static final String RUNTIME_INSTANCE_ID = "s01-bench-runtime";

    private S01AcceptedPolicyStore<S01Policy> store;

    @Setup(Level.Trial)
    public void setUp() {
        store = new S01AcceptedPolicyStore<>(RUNTIME_INSTANCE_ID, S01Policy.of(11L));
    }

    /**
     * Uncontended hot-path read: one volatile reference load plus two final-field reads.
     * Expected {@code gc.alloc.rate.norm} is {@code 0 B/op}.
     */
    @Benchmark
    public double readAcceptedRatio() {
        return store.current().policy().ratio();
    }

    /**
     * Hot-path read that also reaches a reference field of the accepted policy, to show that nested
     * immutable state needs no copy-on-read and still allocates nothing.
     */
    @Benchmark
    public int readAcceptedRouteRatioCount() {
        return store.current().policy().routeRatios().size();
    }

    /** Reads accepted sequence and policy through the same single reference load. */
    @Benchmark
    public long readAcceptedSequenceAndPolicy() {
        S01AcceptedEnvelope<S01Policy> envelope = store.current();
        return envelope.acceptedSequence() + envelope.policy().routeRatios().size();
    }
}
