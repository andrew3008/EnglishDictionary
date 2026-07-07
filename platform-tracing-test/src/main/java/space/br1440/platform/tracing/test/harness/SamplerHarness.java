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

import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class SamplerHarness {

    public static final String DEFAULT_TRACE_ID = "00000000000000000000000000000001";

    private final Sampler sampler;
    private String traceId = DEFAULT_TRACE_ID;
    private String spanName = "test-span";
    private SpanKind spanKind = SpanKind.INTERNAL;
    private Context parentContext = Context.root();
    private final AttributesBuilder attributes = Attributes.builder();
    private List<LinkData> links = Collections.emptyList();

    private SamplerHarness(Sampler sampler) {
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

    public SamplerHarness parentContext(Context parentContext) {
        this.parentContext = Objects.requireNonNull(parentContext, "parentContext");
        return this;
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

     public SamplingResult sample() {
        return sampler.shouldSample(parentContext, traceId, spanName, spanKind, attributes.build(), links);
    }
}
