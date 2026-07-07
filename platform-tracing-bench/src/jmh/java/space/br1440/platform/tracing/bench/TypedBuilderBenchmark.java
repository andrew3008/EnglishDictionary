package space.br1440.platform.tracing.bench;

import io.opentelemetry.api.trace.Tracer;
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
import space.br1440.platform.tracing.api.span.SpanScope;
import space.br1440.platform.tracing.api.semconv.ValidationMode;
import space.br1440.platform.tracing.core.DefaultPlatformTracing;
import space.br1440.platform.tracing.core.exception.ExceptionRecorder;
import space.br1440.platform.tracing.core.impl.DefaultTracingImplementation;
import space.br1440.platform.tracing.core.semconv.AttributePolicy;
import space.br1440.platform.tracing.core.semconv.SemconvMetrics;
import space.br1440.platform.tracing.core.span.HttpServerSpanBuilderImpl;

import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class TypedBuilderBenchmark {

    private OpenTelemetrySdk sdk;
    private DefaultPlatformTracing disabledPolicyTracing;
    private DefaultPlatformTracing warnPolicyTracing;
    private Tracer tracer;
    private AttributePolicy disabledPolicy;
    private AttributePolicy warnPolicy;

    @Setup(Level.Trial)
    public void setUp() {
        sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder().build())
                .build();
        tracer = sdk.getTracer(DefaultTracingImplementation.INSTRUMENTATION_NAME);
        disabledPolicy = new AttributePolicy(ValidationMode.DISABLED, false, SemconvMetrics.NOOP);
        warnPolicy = new AttributePolicy(ValidationMode.WARN, false, SemconvMetrics.NOOP);
        disabledPolicyTracing = new DefaultPlatformTracing(sdk, disabledPolicy);
        warnPolicyTracing = new DefaultPlatformTracing(sdk, warnPolicy);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        sdk.close();
    }

    @Benchmark
    public void facadeStartSpan(Blackhole bh) {
        try (var scope = disabledPolicyTracing.manual().operation("GET /users/{id}").start()) {
            bh.consume(scope);
        }
    }

    @Benchmark
    public void typedHttpServerDisabled(Blackhole bh) {
        try (SpanScope scope = new HttpServerSpanBuilderImpl(tracer, disabledPolicy, ExceptionRecorder.secureDefault())
                .method("GET").route("/users/{id}").start()) {
            bh.consume(scope);
        }
    }

    @Benchmark
    public void typedHttpServerWarn(Blackhole bh) {
        try (SpanScope scope = new HttpServerSpanBuilderImpl(tracer, warnPolicy, ExceptionRecorder.secureDefault())
                .method("GET").route("/users/{id}").start()) {
            bh.consume(scope);
        }
    }
}
