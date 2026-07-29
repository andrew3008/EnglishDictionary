package space.br1440.platform.tracing.test.harness;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import jakarta.annotation.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Plain harness class for testing {@link SpanProcessor} implementations in isolation.
 *
 * <p>Wraps a minimal OTel SDK ({@code SdkTracerProvider} + {@code SimpleSpanProcessor} +
 * {@code InMemorySpanExporter}) so the processor-under-test can be exercised without
 * boilerplate. Must be closed after use ΓÇö either via {@code try-with-resources} or
 * through {@link SpanProcessorHarnessExtension} which manages the lifecycle automatically.
 *
 * <pre>{@code
 * // try-with-resources
 * try (var h = SpanProcessorHarness.of(new EnrichingSpanProcessor())) {
 *     h.tracer().spanBuilder("op").startSpan().end();
 *     assertThat(h.spans()).hasSize(1);
 * }
 *
 * // via extension (auto-close + fresh processor per test)
 * @RegisterExtension
 * static final SpanProcessorHarnessExtension ext =
 *         SpanProcessorHarnessExtension.of(() -> new EnrichingSpanProcessor());
 *
 * @Test
 * void test(SpanProcessorHarness h) {
 *     h.tracer().spanBuilder("op").startSpan().end();
 *     assertThat(h.spans()).hasSize(1);
 * }
 * }</pre>
 *
 * @since 5.4
 */
public final class SpanProcessorHarness implements AutoCloseable {

    private static final String DEFAULT_TRACER_NAME = "test";
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 2;

    private final OpenTelemetrySdk sdk;
    private final InMemorySpanExporter exporter;
    private final Tracer defaultTracer;

    private SpanProcessorHarness(OpenTelemetrySdk sdk, InMemorySpanExporter exporter) {
        this.sdk = sdk;
        this.exporter = exporter;
        this.defaultTracer = sdk.getTracer(DEFAULT_TRACER_NAME);
    }

    // -------------------------------------------------------------------------
    // Factory methods
    // -------------------------------------------------------------------------

    /**
     * Creates a harness wiring {@code processorUnderTest} into a minimal SDK.
     *
     * @param processorUnderTest the processor to test; must not be {@code null}
     * @return a new, open harness
     */
    public static SpanProcessorHarness of(SpanProcessor processorUnderTest) {
        return of(processorUnderTest, null);
    }

    /**
     * Creates a harness with a custom {@link Resource} attached to the tracer provider.
     *
     * @param processorUnderTest the processor to test; must not be {@code null}
     * @param resource           optional resource; {@code null} means SDK default
     * @return a new, open harness
     */
    public static SpanProcessorHarness of(SpanProcessor processorUnderTest,
                                          @Nullable Resource resource) {
        Objects.requireNonNull(processorUnderTest, "processorUnderTest");

        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SdkTracerProviderBuilder builder = SdkTracerProvider.builder()
                .addSpanProcessor(processorUnderTest)
                .addSpanProcessor(SimpleSpanProcessor.create(exporter));

        if (resource != null) {
            builder.setResource(resource);
        }

        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(builder.build())
                .build();

        return new SpanProcessorHarness(sdk, exporter);
    }

    // -------------------------------------------------------------------------
    // Test helpers
    // -------------------------------------------------------------------------

    /**
     * Returns the default tracer named {@code "test"}.
     * Sufficient for the vast majority of processor unit-tests.
     *
     * @return tracer scoped to instrumentation name {@code "test"}
     */
    public Tracer tracer() {
        return defaultTracer;
    }

    /**
     * Returns a tracer with a custom instrumentation scope name.
     * Use when the processor-under-test inspects {@code InstrumentationScopeInfo}.
     *
     * @param instrumentationScopeName non-null scope name
     * @return tracer for that scope
     */
    public Tracer tracer(String instrumentationScopeName) {
        return sdk.getTracer(
                Objects.requireNonNull(instrumentationScopeName, "instrumentationScopeName"));
    }

    /**
     * Returns all finished spans collected so far.
     * Delegates to {@link InMemorySpanExporter#getFinishedSpanItems()}.
     *
     * @return live list of finished spans (not a defensive copy)
     */
    public List<SpanData> spans() {
        return exporter.getFinishedSpanItems();
    }

    /**
     * Clears the in-memory exporter so the harness is ready for the next assertion.
     * Called automatically by {@link SpanProcessorHarnessExtension} in
     * {@code beforeEach} (via fresh-harness creation).
     */
    public void reset() {
        exporter.reset();
    }

    /**
     * Exposes the underlying {@link InMemorySpanExporter} for callers that prefer
     * {@code OpenTelemetryAssertions.assertThat(exporter)} assertions.
     *
     * @return the exporter
     */
    public InMemorySpanExporter exporter() {
        return exporter;
    }

    /**
     * Exposes the full {@link OpenTelemetry} instance for tests that need
     * propagation or context APIs in addition to span capture.
     *
     * @return the underlying SDK
     */
    public OpenTelemetry openTelemetry() {
        return sdk;
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Shuts down the underlying {@link OpenTelemetrySdk}.
     * Waits up to {@value #SHUTDOWN_TIMEOUT_SECONDS} seconds for pending exports.
     *
     * <p>Safe to call multiple times (SDK shutdown is idempotent).
     */
    @Override
    public void close() {
        sdk.getSdkTracerProvider()
                .forceFlush()
                .join(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        sdk.close();
    }
}
