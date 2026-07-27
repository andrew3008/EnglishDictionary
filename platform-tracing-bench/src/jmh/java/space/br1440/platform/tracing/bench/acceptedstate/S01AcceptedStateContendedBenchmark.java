package space.br1440.platform.tracing.bench.acceptedstate;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Group;
import org.openjdk.jmh.annotations.GroupThreads;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.concurrent.TimeUnit;

/**
 * S-01 Gate E3 feasibility evidence: accepted-state reads under a continuous commit storm.
 * <p>
 * Seven reader threads stay on the hot path while one writer performs single-shot expected-base
 * commits. Two things are being shown: readers keep a zero-allocation, lock-free read even while
 * commits land, and the commit path does not amplify under contention, because a losing attempt is
 * reported as {@code STALE} rather than rebuilt and retried.
 * <p>
 * The commit rate here is far above real operation, where policy changes arrive a handful of times per
 * minute. It is an intentional upper-bound stress mode, not a budget.
 */
@State(Scope.Group)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class S01AcceptedStateContendedBenchmark {

    private static final String RUNTIME_INSTANCE_ID = "s01-bench-runtime";

    private S01AcceptedPolicyStore<S01Policy> store;
    private long commitSeed;

    @Setup(Level.Trial)
    public void setUp() {
        store = new S01AcceptedPolicyStore<>(RUNTIME_INSTANCE_ID, S01Policy.of(0L));
        commitSeed = 0L;
    }

    /** Readers under a continuous commit storm: seven threads on the hot path. */
    @Benchmark
    @Group("readWhileCommit")
    @GroupThreads(7)
    public double contendedRead() {
        return store.current().policy().ratio();
    }

    /**
     * Single writer performing single-shot expected-base commits: read the base, build one candidate,
     * attempt exactly one {@code compareAndSet}. A losing attempt returns without rebuilding.
     */
    @Benchmark
    @Group("readWhileCommit")
    @GroupThreads(1)
    public S01CommitOutcome contendedCommit() {
        S01ExpectedBase base = store.current().expectedBase();
        return store.commit(base, S01Policy.of(++commitSeed)).outcome();
    }
}
