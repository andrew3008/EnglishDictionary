package space.br1440.platform.tracing.bench;

import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;
import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.core.runtime.otel.OtelTracingRuntimeFactory;
import space.br1440.platform.tracing.core.facade.DefaultTraceOperations;

import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class StartSpanBenchmark {

    private OpenTelemetrySdk sdk;
    private TraceOperations traceOperations;

    @Setup(Level.Trial)
    public void setUp() {
        sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder().build())
                .build();
        traceOperations = new DefaultTraceOperations(OtelTracingRuntimeFactory.create(sdk));
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        sdk.close();
    }

    @Benchmark
    public void startAndCloseSpan(Blackhole bh) {
        try (SpanHandle scope = traceOperations.spans().operation("bench-op").start()) {
            bh.consume(scope);
        }
    }
}
