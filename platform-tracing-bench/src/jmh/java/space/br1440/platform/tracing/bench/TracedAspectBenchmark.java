package space.br1440.platform.tracing.bench;

import io.opentelemetry.api.trace.Span;
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
import space.br1440.platform.tracing.api.PlatformTracing;
import space.br1440.platform.tracing.api.span.SpanResult;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.core.facade.DefaultPlatformTracing;

import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class TracedAspectBenchmark {

    private OpenTelemetrySdk sdk;
    private PlatformTracing platformTracing;

    @Setup(Level.Trial)
    public void setUp() {
        sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder().build())
                .build();
        platformTracing = new DefaultPlatformTracing(sdk);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        sdk.close();
    }

    @Benchmark
    public void startAttributesResultClose(Blackhole bh) {
        try (SpanHandle scope = platformTracing.manual().operation("bench-method").start()) {
            Span.current().setAttribute("code.namespace", "space.br1440.bench");
            Span.current().setAttribute("code.function", "startAttributesResultClose");
            Span.current().setAttribute(PlatformAttributes.PLATFORM_RESULT, SpanResult.SUCCESS.value());
            bh.consume(scope);
        }
    }
}
