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
 * P5.7 budget benchmark — SHA-256 digest of 8-leaf canonical policy.
 *
 * <p>Budget (AverageTime, JDK 21):
 * <ul>
 *   <li>no routes  ≤ 800 ns/op</li>
 *   <li>five routes ≤ 1 200 ns/op</li>
 * </ul>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(value = 3, warmups = 2)
@Warmup(iterations = 3, time = 5)
@Measurement(iterations = 5, time = 5)
public class P57AcceptedStateDigestBenchmark {

    @Benchmark
    public void digestNoRoutes(AcceptedStateBenchmarkFixtures fixtures, Blackhole bh) {
        bh.consume(EffectiveTracingPolicyDigestAccess.sha256(
                fixtures.accessNoRoutes.current()));
    }

    @Benchmark
    public void digestFiveRoutes(AcceptedStateBenchmarkFixtures fixtures, Blackhole bh) {
        bh.consume(EffectiveTracingPolicyDigestAccess.sha256(
                fixtures.accessFiveRoutes.current()));
    }
}
