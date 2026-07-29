package space.br1440.platform.tracing.bench.acceptedstate;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import space.br1440.platform.tracing.otel.javaagent.control.state.AcceptedPolicyAccess;
import space.br1440.platform.tracing.otel.javaagent.control.state.AcceptedPolicyBenchmarkAccess;
import space.br1440.platform.tracing.otel.javaagent.control.state.AcceptedPolicyUpdateOutcome;

/**
 * P5.7 apply-path benchmarks with an outcome invariant on every measured invocation.
 *
 * <p>APPLIED alternates between two pre-built policies and wins one expected-base CAS each time.
 * STALE repeatedly submits one captured stale base without retry or rebase. UNCHANGED exercises
 * the production sampling fast-path and never constructs a candidate or reaches CAS.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(value = 3, warmups = 2)
@Warmup(iterations = 3, time = 5, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 5, timeUnit = TimeUnit.SECONDS)
public class P57AcceptedStateApplyBenchmark {

    @Benchmark
    public AcceptedPolicyUpdateOutcome applyUnchanged(UnchangedState state) {
        AcceptedPolicyUpdateOutcome outcome =
                state.access.updateSampling(true, 1.0d, state.emptyRoutes);
        return requireOutcome(outcome, AcceptedPolicyUpdateOutcome.UNCHANGED);
    }

    @Benchmark
    public AcceptedPolicyUpdateOutcome applyApplied(AppliedState state) {
        return state.access.applyNext();
    }

    @Benchmark
    public AcceptedPolicyUpdateOutcome applyStale(StaleState state) {
        return state.access.applyStale();
    }

    private static AcceptedPolicyUpdateOutcome requireOutcome(
            AcceptedPolicyUpdateOutcome actual,
            AcceptedPolicyUpdateOutcome expected) {
        if (actual != expected) {
            throw new IllegalStateException(
                    "Benchmark path drift: expected " + expected + ", got " + actual);
        }
        return actual;
    }

    @State(Scope.Benchmark)
    public static class UnchangedState {
        private AcceptedPolicyAccess access;
        private Map<String, Double> emptyRoutes;

        @Setup
        public void setUp() {
            access = AcceptedPolicyAccess.isolatedDefaults();
            emptyRoutes = Map.of();
        }
    }

    @State(Scope.Benchmark)
    public static class AppliedState {
        private AcceptedPolicyBenchmarkAccess access;

        @Setup
        public void setUp() {
            access = AcceptedPolicyBenchmarkAccess.appliedPath();
        }
    }

    @State(Scope.Benchmark)
    public static class StaleState {
        private AcceptedPolicyBenchmarkAccess access;

        @Setup
        public void setUp() {
            access = AcceptedPolicyBenchmarkAccess.stalePath();
        }
    }
}
