package space.br1440.platform.tracing.otel.javaagent.control.state;

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
import org.openjdk.jmh.infra.Blackhole;

import javax.management.openmbean.CompositeData;
import java.util.concurrent.TimeUnit;

/**
 * P5.7 budget benchmark — Agent-side encode + Spring-side decode for PRIV-R07.
 *
 * <p>Budget (AverageTime, JDK 21):
 * <ul>
 *   <li>encode no routes   ≤ 15 000 ns/op</li>
 *   <li>encode five routes ≤ 20 000 ns/op</li>
 *   <li>decode no routes   ≤ 20 000 ns/op</li>
 *   <li>decode five routes ≤ 25 000 ns/op</li>
 * </ul>
 *
 * <p>The {@link CodecState} secondary fixture pre-encodes one snapshot per trial
 * so the decode benchmarks can operate on already-built {@link CompositeData}.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(value = 3, warmups = 2)
@Warmup(iterations = 3, time = 5)
@Measurement(iterations = 5, time = 5)
public class P57AcceptedStateCodecBenchmark {

    @State(Scope.Benchmark)
    public static class CodecState {
        public CompositeData encodedNoRoutes;
        public CompositeData encodedFiveRoutes;

        @Setup(Level.Trial)
        public void setup(AcceptedStateBenchmarkFixtures fixtures) {
            encodedNoRoutes = fixtures.codecAccess.encodeAcceptedComplete(
                    fixtures.accessNoRoutes.current());
            encodedFiveRoutes = fixtures.codecAccess.encodeAcceptedComplete(
                    fixtures.accessFiveRoutes.current());
        }
    }

    @Benchmark
    public void encodeNoRoutes(AcceptedStateBenchmarkFixtures fixtures, Blackhole bh) {
        bh.consume(fixtures.codecAccess.encodeAcceptedComplete(
                fixtures.accessNoRoutes.current()));
    }

    @Benchmark
    public void encodeFiveRoutes(AcceptedStateBenchmarkFixtures fixtures, Blackhole bh) {
        bh.consume(fixtures.codecAccess.encodeAcceptedComplete(
                fixtures.accessFiveRoutes.current()));
    }

    @Benchmark
    public void decodeNoRoutes(AcceptedStateBenchmarkFixtures fixtures,
                               CodecState codec,
                               Blackhole bh) {
        bh.consume(fixtures.codecAccess.decode(codec.encodedNoRoutes));
    }

    @Benchmark
    public void decodeFiveRoutes(AcceptedStateBenchmarkFixtures fixtures,
                                 CodecState codec,
                                 Blackhole bh) {
        bh.consume(fixtures.codecAccess.decode(codec.encodedFiveRoutes));
    }
}
