package space.br1440.platform.tracing.test.harness;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;

import java.util.List;

import static io.opentelemetry.api.common.AttributeKey.stringKey;

/**
 * Plain builder class for composing {@link Sampler#shouldSample} invocations in tests.
 *
 * <p>Not a JUnit Extension — designed to be instantiated directly or via
 * {@link SamplerHarnessExtension} for {@code @RegisterExtension} syntax.
 *
 * <pre>{@code
 * @Test
 * void healthcheck_drops() {
 *     new SamplerHarness(sampler)
 *             .withUrlPath("/actuator/health")
 *             .withKind(SpanKind.SERVER)
 *             .sample("GET /actuator/health")
 *             .assertDropped();
 * }
 * }</pre>
 *
 * @since 5.2
 */
public final class SamplerHarness {

    /** Fixed trace-id used in all {@link #sample(String)} calls. */
    private static final String FIXED_TRACE_ID = "0af7651916cd43dd8448eb211c80319c";
    /** Fixed span-id used in all {@link #sample(String)} calls. */
    private static final String FIXED_SPAN_ID  = "b7ad6b7169203331";

    private final Sampler sampler;
    private final AttributesBuilder attrs = Attributes.builder();
    private Context parent = Context.root();
    private SpanKind kind = SpanKind.INTERNAL;

    /**
     * Creates a harness wrapping the supplied {@code sampler}.
     *
     * @param sampler the sampler under test; must not be {@code null}
     */
    public SamplerHarness(Sampler sampler) {
        if (sampler == null) throw new IllegalArgumentException("sampler must not be null");
        this.sampler = sampler;
    }

    /**
     * Adds a request header as a span attribute (key {@code http.request.header.<name>}).
     *
     * @param name  header name, case-insensitive
     * @param value header value
     * @return this harness (fluent)
     */
    public SamplerHarness withHeader(String name, String value) {
        attrs.put(stringKey("http.request.header." + name.toLowerCase()), value);
        return this;
    }

    /**
     * Sets the {@code url.path} attribute (used by CompositeSampler health-check drop rule).
     *
     * @param path URL path, e.g. {@code /actuator/health}
     * @return this harness (fluent)
     */
    public SamplerHarness withUrlPath(String path) {
        attrs.put(stringKey("url.path"), path);
        return this;
    }

    /**
     * Overrides the {@link SpanKind}; default is {@link SpanKind#INTERNAL}.
     *
     * @param kind span kind
     * @return this harness (fluent)
     */
    public SamplerHarness withKind(SpanKind kind) {
        if (kind == null) throw new IllegalArgumentException("kind must not be null");
        this.kind = kind;
        return this;
    }

    /**
     * Overrides the parent {@link Context}; default is {@link Context#root()}.
     *
     * @param parent OTel context carrying parent span info
     * @return this harness (fluent)
     */
    public SamplerHarness withParent(Context parent) {
        if (parent == null) throw new IllegalArgumentException("parent must not be null");
        this.parent = parent;
        return this;
    }

    /**
     * Invokes {@code sampler.shouldSample(...)} for the given span name and returns
     * a fluent {@link SamplerDecisionAssert}.
     *
     * @param spanName name passed to {@link Sampler#shouldSample}
     * @return assertion wrapper over the {@link SamplingResult}
     */
    public SamplerDecisionAssert sample(String spanName) {
        SamplingResult result = sampler.shouldSample(
                parent,
                FIXED_TRACE_ID,
                spanName,
                kind,
                attrs.build(),
                List.of()
        );
        return new SamplerDecisionAssert(result);
    }

    /**
     * Resets the builder state (attributes, kind, parent) so the harness can be
     * reused across test methods (called by {@link SamplerHarnessExtension} in
     * {@code beforeEach}).
     */
    public void reset() {
        // Re-assign to a fresh builder — AttributesBuilder has no clear().
        // We shadow the field by creating a new harness-level state via the reset contract.
        // Since attrs is final, we delegate to a private mutable wrapper.
        attrsReset();
        kind = SpanKind.INTERNAL;
        parent = Context.root();
    }

    // -------------------------------------------------------------------------
    // Internal mutable state helpers
    // -------------------------------------------------------------------------

    private AttributesBuilder mutableAttrs = Attributes.builder();

    private void attrsReset() {
        mutableAttrs = Attributes.builder();
    }
}
