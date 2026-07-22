package space.br1440.platform.tracing.bench;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
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
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.otel.validation.ValidationSnapshot;
import space.br1440.platform.tracing.otel.javaagent.exception.TracingValidationException;
import space.br1440.platform.tracing.otel.javaagent.processor.ValidationPolicyHolder;
import space.br1440.platform.tracing.otel.javaagent.processor.ValidatingSpanProcessor;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * Dedicated validation hot-path microbenchmarks (PR-9H-A, W-003).
 * <p>
 * Measures {@link ValidatingSpanProcessor#onEnding} via {@code Span.end()} on real SDK spans
 * (production path). Isolated holder/snapshot reads measure lock-free policy access without
 * span lifecycle overhead.
 * <p>
 * Hard-gate candidates: {@link #validationDisabled}, {@link #validationLenientValidSpan},
 * {@link #validationLenientMissingRequiredAttr}, {@link #validationStrictAllowedValidSpan},
 * {@link #holderCurrentRead}, {@link #policySnapshotRead}.
 * <p>
 * Diagnostic-only: {@link #validationStrictAllowedMissingAttrDiagnostic} — strict exception path,
 * not production hot path; exclude from official baseline gate until PR-9H-B wiring.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class ValidatingSpanProcessorBenchmark {

    private ValidatingSpanProcessor disabledProcessor;
    private ValidatingSpanProcessor lenientProcessor;
    private ValidatingSpanProcessor lenientMissingProcessor;
    private ValidatingSpanProcessor strictValidProcessor;
    private ValidatingSpanProcessor strictMissingProcessor;

    private ValidationPolicyHolder lenientHolder;
    private ValidationSnapshot lenientSnapshot;

    private OpenTelemetrySdk disabledSdk;
    private OpenTelemetrySdk lenientSdk;
    private OpenTelemetrySdk lenientMissingSdk;
    private OpenTelemetrySdk strictValidSdk;
    private OpenTelemetrySdk strictMissingSdk;

    private Tracer disabledTracer;
    private Tracer lenientTracer;
    private Tracer lenientMissingTracer;
    private Tracer strictValidTracer;
    private Tracer strictMissingTracer;

    @Setup(Level.Trial)
    public void setUp() {
        disabledProcessor = new ValidatingSpanProcessor(false);
        disabledProcessor.tryApplyPolicyUpdate(false, false, "bench");

        lenientProcessor = new ValidatingSpanProcessor(false);
        lenientMissingProcessor = new ValidatingSpanProcessor(false);
        strictValidProcessor = new ValidatingSpanProcessor(false, true);
        strictValidProcessor.tryApplyPolicyUpdate(true, true, "bench");
        strictMissingProcessor = new ValidatingSpanProcessor(true, true);

        lenientHolder = new ValidationPolicyHolder(
                ValidationSnapshot.fromPolicy(true, false, 1, Instant.EPOCH, "bench"));
        lenientSnapshot = lenientHolder.current();

        disabledSdk = buildSdk(disabledProcessor);
        lenientSdk = buildSdk(lenientProcessor);
        lenientMissingSdk = buildSdk(lenientMissingProcessor);
        strictValidSdk = buildSdk(strictValidProcessor);
        strictMissingSdk = buildSdk(strictMissingProcessor);

        disabledTracer = disabledSdk.getTracer("validation-bench");
        lenientTracer = lenientSdk.getTracer("validation-bench");
        lenientMissingTracer = lenientMissingSdk.getTracer("validation-bench");
        strictValidTracer = strictValidSdk.getTracer("validation-bench");
        strictMissingTracer = strictMissingSdk.getTracer("validation-bench");

        // Prime rate-limiter for missing-attr path so measurement is not dominated by log I/O.
        lenientMissingTracer.spanBuilder("warmup-missing").startSpan().end();
    }

    private static OpenTelemetrySdk buildSdk(ValidatingSpanProcessor processor) {
        return OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder()
                        .setResource(Resource.empty())
                        .addSpanProcessor(processor)
                        .build())
                .build();
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        disabledSdk.close();
        lenientSdk.close();
        lenientMissingSdk.close();
        strictValidSdk.close();
        strictMissingSdk.close();
    }

    /** validation.enabled=false passthrough on {@code onEnding}. */
    @Benchmark
    public void validationDisabled(Blackhole bh) {
        Span span = disabledTracer.spanBuilder("bench-disabled").startSpan();
        span.end();
        bh.consume(span);
    }

    /** Production lenient path: required attrs present. */
    @Benchmark
    public void validationLenientValidSpan(Blackhole bh) {
        Span span = lenientTracer.spanBuilder("bench-lenient-valid")
                .setAttribute(PlatformAttributes.PLATFORM_TYPE, "HTTP")
                .setAttribute(PlatformAttributes.PLATFORM_RESULT, "success")
                .startSpan();
        span.end();
        bh.consume(span);
    }

    /** Lenient missing-required path: annotate + throttled warn (post-warmup). */
    @Benchmark
    public void validationLenientMissingRequiredAttr(Blackhole bh) {
        Span span = lenientMissingTracer.spanBuilder("bench-lenient-missing").startSpan();
        span.end();
        bh.consume(span);
    }

    /** Strict mode with required attrs (diagnostic/pre-prod profile). */
    @Benchmark
    public void validationStrictAllowedValidSpan(Blackhole bh) {
        Span span = strictValidTracer.spanBuilder("bench-strict-valid")
                .setAttribute(PlatformAttributes.PLATFORM_TYPE, "HTTP")
                .setAttribute(PlatformAttributes.PLATFORM_RESULT, "success")
                .startSpan();
        span.end();
        bh.consume(span);
    }

    /** Lock-free {@code ValidationPolicyHolder.current()} read. */
    @Benchmark
    public void holderCurrentRead(Blackhole bh) {
        ValidationSnapshot snapshot = lenientHolder.current();
        bh.consume(snapshot.enabled());
        bh.consume(snapshot.strict());
        bh.consume(snapshot.version());
    }

    /** Immutable snapshot field access (no holder indirection). */
    @Benchmark
    public void policySnapshotRead(Blackhole bh) {
        bh.consume(lenientSnapshot.enabled());
        bh.consume(lenientSnapshot.strict());
        bh.consume(lenientSnapshot.version());
        bh.consume(lenientSnapshot.source());
    }

    /**
     * Diagnostic-only: strict exception on missing attrs. Not a production hot path;
     * exclude from hard gate ({@code -PjmhExclude=.*MissingAttrDiagnostic}).
     */
    @Benchmark
    public void validationStrictAllowedMissingAttrDiagnostic(Blackhole bh) {
        try {
            strictMissingTracer.spanBuilder("bench-strict-missing").startSpan().end();
        } catch (TracingValidationException ex) {
            bh.consume(ex.getMessage());
        }
    }
}
