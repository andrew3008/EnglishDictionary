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
import space.br1440.platform.tracing.api.semconv.ValidationMode;
import space.br1440.platform.tracing.core.runtime.otel.OtelTracingRuntimeFactory;
import space.br1440.platform.tracing.core.facade.DefaultPlatformTracing;
import space.br1440.platform.tracing.core.semconv.policy.AttributePolicy;
import space.br1440.platform.tracing.core.semconv.policy.SemconvMetrics;

import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class TypedBuilderBenchmark {

    private OpenTelemetrySdk sdk;
    private DefaultPlatformTracing disabledPolicyTracing;
    private DefaultPlatformTracing warnPolicyTracing;

    @Setup(Level.Trial)
    public void setUp() {
        sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder().build())
                .build();
        AttributePolicy disabledPolicy = new AttributePolicy(ValidationMode.DISABLED, false, SemconvMetrics.NOOP);
        AttributePolicy warnPolicy = new AttributePolicy(ValidationMode.WARN, false, SemconvMetrics.NOOP);
        disabledPolicyTracing = new DefaultPlatformTracing(OtelTracingRuntimeFactory.create(sdk, disabledPolicy);
        warnPolicyTracing = new DefaultPlatformTracing(OtelTracingRuntimeFactory.create(sdk, warnPolicy)));
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
        try (var scope = disabledPolicyTracing.manual().transport().http().server()
                .method("GET").route("/users/{id}").start()) {
            bh.consume(scope);
        }
    }

    @Benchmark
    public void typedHttpServerWarn(Blackhole bh) {
        try (var scope = warnPolicyTracing.manual().transport().http().server()
                .method("GET").route("/users/{id}").start()) {
            bh.consume(scope);
        }
    }
}
