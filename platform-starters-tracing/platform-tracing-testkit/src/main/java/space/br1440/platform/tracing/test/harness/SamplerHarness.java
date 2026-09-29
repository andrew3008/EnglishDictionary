package space.br1440.platform.tracing.test.harness;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import space.br1440.platform.tracing.test.assertions.SamplerDecisionAssert;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;

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

    public SamplerHarness spanKind(SpanKind spanKind) {
        this.spanKind = Objects.requireNonNull(spanKind, "spanKind");
        return this;
    }

    public SamplerHarness withKind(SpanKind kind) {
        return spanKind(kind);
    }

    public SamplerHarness parentContext(Context parentContext) {
        this.parentContext = Objects.requireNonNull(parentContext, "parentContext");
        return this;
    }

    public void withParent(Context parent) {
        parentContext(parent);
    }

    public SamplerHarness putStringAttribute(String key, String value) {
        attributes.put(AttributeKey.stringKey(key), value);
        return this;
    }

    public SamplerHarness putStringArrayAttribute(String key, String... values) {
        attributes.put(AttributeKey.stringArrayKey(key), List.of(values));
        return this;
    }

    public SamplerHarness withHeader(String name, String value) {
        Objects.requireNonNull(name, "name");
        return putStringAttribute(
                "http.request.header." + name.toLowerCase(Locale.ROOT),
                value);
    }

    public SamplerHarness withUrlPath(String path) {
        return putStringAttribute("url.path", path);
    }

    public Attributes attributesSnapshot() {
        return attributes.build();
    }

    public Sampler sampler() {
        return sampler;
    }

    public SamplingResult sample() {
        return sampler.shouldSample(parentContext, traceId, spanName, spanKind, attributes.build(), links);
    }

    public SamplerDecisionAssert sample(String spanName) {
        this.spanName = Objects.requireNonNull(spanName, "spanName");
        return SamplerDecisionAssert.assertThat(sample());
    }

    public void reset() {
        attributes = Attributes.builder();
        spanKind = SpanKind.INTERNAL;
        parentContext = Context.root();
        spanName = "test-span";
        traceId = DEFAULT_TRACE_ID;
        links = Collections.emptyList();
    }
}
