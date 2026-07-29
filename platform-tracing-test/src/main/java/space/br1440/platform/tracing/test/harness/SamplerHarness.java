package space.br1440.platform.tracing.test.harness;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;
import jakarta.annotation.Nullable;
import space.br1440.platform.tracing.test.assertions.SamplerDecisionAssert;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Plain builder for composing {@link Sampler#shouldSample} invocations in tests.
 *
 * <p>Not a JUnit Extension — instantiate directly or via {@link space.br1440.platform.tracing.test.junit.SamplerHarnessExtension}.
 *
 * @since 5.2
 */
public final class SamplerHarness {

    public static final String DEFAULT_TRACE_ID = "00000000000000000000000000000001";

    private final Sampler sampler;
    private String traceId = DEFAULT_TRACE_ID;
    private String spanName = "test-span";
    private SpanKind spanKind = SpanKind.INTERNAL;
    private Context parentContext = Context.root();
    private AttributesBuilder attributes = Attributes.builder();
    private List<LinkData> links = Collections.emptyList();

    public SamplerHarness(Sampler sampler) {
        this.sampler = Objects.requireNonNull(sampler, "sampler");
    }

    public static SamplerHarness of(Sampler sampler) {
        return new SamplerHarness(sampler);
    }

    public SamplerHarness traceId(String traceId) {
        this.traceId = Objects.requireNonNull(traceId, "traceId");
        return this;
    }

    public SamplerHarness spanName(String spanName) {
        this.spanName = Objects.requireNonNull(spanName, "spanName");
        return this;
    }

    public SamplerHarness spanKind(SpanKind spanKind) {
        this.spanKind = Objects.requireNonNull(spanKind, "spanKind");
        return this;
    }

    /** Plan §5.2 alias for {@link #spanKind(SpanKind)}. */
    public SamplerHarness withKind(SpanKind kind) {
        return spanKind(kind);
    }

    public SamplerHarness parentContext(Context parentContext) {
        this.parentContext = Objects.requireNonNull(parentContext, "parentContext");
        return this;
    }

    /** Plan §5.2 alias for {@link #parentContext(Context)}. */
    public SamplerHarness withParent(Context parent) {
        return parentContext(parent);
    }

    public <T> SamplerHarness putAttribute(AttributeKey<T> key, T value) {
        attributes.put(key, value);
        return this;
    }

    public SamplerHarness putStringAttribute(String key, String value) {
        attributes.put(AttributeKey.stringKey(key), value);
        return this;
    }

    public SamplerHarness putStringArrayAttribute(String key, String... values) {
        attributes.put(AttributeKey.stringArrayKey(key), List.of(values));
        return this;
    }

    /**
     * Adds {@code http.request.header.<name>} (lower-cased name) as a string attribute.
     */
    public SamplerHarness withHeader(String name, String value) {
        Objects.requireNonNull(name, "name");
        return putStringAttribute(
                "http.request.header." + name.toLowerCase(Locale.ROOT),
                value);
    }

    /**
     * Sets {@code url.path} (used by health-check drop rules).
     */
    public SamplerHarness withUrlPath(String path) {
        return putStringAttribute("url.path", path);
    }

    public SamplerHarness links(@Nullable List<LinkData> links) {
        this.links = (links == null) ? Collections.emptyList() : List.copyOf(links);
        return this;
    }

    public Attributes attributesSnapshot() {
        return attributes.build();
    }

    public Sampler sampler() {
        return sampler;
    }

    /**
     * Invokes {@code shouldSample} with the current builder state and the harness span name.
     */
    public SamplingResult sample() {
        return sampler.shouldSample(parentContext, traceId, spanName, spanKind, attributes.build(), links);
    }

    /**
     * Invokes {@code shouldSample} for {@code spanName} and returns a fluent assert wrapper.
     */
    public SamplerDecisionAssert sample(String spanName) {
        this.spanName = Objects.requireNonNull(spanName, "spanName");
        return SamplerDecisionAssert.assertThat(sample());
    }

    /**
     * Resets builder state so the harness can be reused across test methods
     * (called by {@code SamplerHarnessExtension} in {@code beforeEach}).
     */
    public void reset() {
        attributes = Attributes.builder();
        spanKind = SpanKind.INTERNAL;
        parentContext = Context.root();
        spanName = "test-span";
        traceId = DEFAULT_TRACE_ID;
        links = Collections.emptyList();
    }
}
