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

import space.br1440.platform.tracing.autoconfigure.configuration.AcceptedSnapshotWithSequence;
import space.br1440.platform.tracing.autoconfigure.jmx.privatecontrol.PrivateControlOpenTypeCodec;
import space.br1440.platform.tracing.otel.javaagent.control.state.EffectiveTracingPolicyDigestAccess;
import space.br1440.platform.tracing.otel.javaagent.control.state.PrivateControlOpenTypeCodecAccess;

import javax.management.openmbean.CompositeData;
import java.util.concurrent.TimeUnit;

/**
 * P5.7 §20 — Agent-side encode and Spring-side decode benchmarks for
 * the {@code readAcceptedComplete} (PRIV-R07) wire operation.
 *
 * <h2>Why these numbers matter</h2>
 * <p>{@code encodeAcceptedComplete} and {@code decodeCompleteWithSequence} run on the
 * <em>control plane</em>, not the request hot path. Policy reads happen at most a few times
 * per minute in production. The codec benchmarks confirm that even if called more
 * frequently (e.g. during a health-probe loop), neither side becomes a throughput bottleneck.
 * OpenType {@code CompositeData} construction is intentionally heavyweight; the budgets below
 * document the expected cost range and protect against accidental regression.
 *
 * <h2>P5.7 budget contract (AverageTime, JDK 21, windows-i5-13500)</h2>
 * <ul>
 *   <li>{@code encodeNoRoutes}    ≤ 15 000 ns/op</li>
 *   <li>{@code encodeFiveRoutes}  ≤ 20 000 ns/op</li>
 *   <li>{@code decodeNoRoutes}    ≤ 20 000 ns/op</li>
 *   <li>{@code decodeFiveRoutes}  ≤ 25 000 ns/op</li>
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
public class P57AcceptedStateCodecBenchmark extends AcceptedStateBenchmarkFixtures {

    // ── Agent-side encode ──────────────────────────────────────────────────────────

    /**
     * Agent {@code PrivateControlOpenTypeCodec.encodeAcceptedComplete(view)}, zero routes.
     * Allocates one {@code CompositeData} + internal arrays. Budget: ≤ 15 000 ns/op.
     */
    @Benchmark
    public CompositeData encodeNoRoutes() {
        return PrivateControlOpenTypeCodecAccess.encodeAcceptedComplete(viewNoRoutes);
    }

    /**
     * Agent {@code PrivateControlOpenTypeCodec.encodeAcceptedComplete(view)}, five routes.
     * Allocates one {@code CompositeData} + one {@code TabularData} with five rows.
     * Budget: ≤ 20 000 ns/op.
     */
    @Benchmark
    public CompositeData encodeFiveRoutes() {
        return PrivateControlOpenTypeCodecAccess.encodeAcceptedComplete(viewFiveRoutes);
    }

    // ── Spring-side decode ────────────────────────────────────────────────────────

    /**
     * Spring {@link PrivateControlOpenTypeCodec#decodeCompleteWithSequence(Object)},
     * pre-encoded zero-route payload from trial setup.
     * Allocates one {@link AcceptedSnapshotWithSequence} + one empty {@code Map}.
     * Budget: ≤ 20 000 ns/op.
     */
    @Benchmark
    public AcceptedSnapshotWithSequence decodeNoRoutes() {
        return springCodec.decodeCompleteWithSequence(encodedNoRoutes);
    }

    /**
     * Spring {@link PrivateControlOpenTypeCodec#decodeCompleteWithSequence(Object)},
     * pre-encoded five-route payload from trial setup.
     * Allocates one {@link AcceptedSnapshotWithSequence} + one {@code LinkedHashMap} with five entries.
     * Budget: ≤ 25 000 ns/op.
     */
    @Benchmark
    public AcceptedSnapshotWithSequence decodeFiveRoutes() {
        return springCodec.decodeCompleteWithSequence(encodedFiveRoutes);
    }
}
