package space.br1440.platform.tracing.core.runtime.otel;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.TraceStateBuilder;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.api.manual.TraceContextView;
import space.br1440.platform.tracing.api.span.spec.SpanAttributeValue;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.api.span.spec.SpanOptions;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.api.span.spec.Topology;
import space.br1440.platform.tracing.core.exception.ExceptionRecorder;
import space.br1440.platform.tracing.core.context.DefaultTraceContextView;
import space.br1440.platform.tracing.core.runtime.NoOpSpanHandle;
import space.br1440.platform.tracing.core.runtime.SpanHandleImpl;
import space.br1440.platform.tracing.core.runtime.TracingRuntime;
import space.br1440.platform.tracing.core.runtime.state.ImmutableTracingState;
import space.br1440.platform.tracing.core.runtime.state.TracingMode;
import space.br1440.platform.tracing.core.runtime.state.TracingState;
import space.br1440.platform.tracing.core.semconv.policy.AttributePolicy;
import space.br1440.platform.tracing.core.runtime.otel.context.PlatformSpanContextKeys;
import space.br1440.platform.tracing.core.runtime.otel.scope.OwningSpanScope;
import space.br1440.platform.tracing.core.runtime.otel.SpanKinds;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Default {@link TracingRuntime} backed by OpenTelemetry SDK/API (Slice 2).
 */
public final class OtelTracingRuntime implements TracingRuntime {

    public static final String INSTRUMENTATION_NAME = "space.br1440.platform.tracing";

    private final Tracer tracer;
    private final AttributePolicy attributePolicy;
    private final ExceptionRecorder exceptionRecorder;
    private final TraceContextView traceContextView;
    private final AtomicBoolean killSwitchEnabled = new AtomicBoolean(true);

    public OtelTracingRuntime(@Nonnull OpenTelemetry openTelemetry,
                                        @Nonnull AttributePolicy policy,
                                        @Nonnull ExceptionRecorder exceptionRecorder) {
        Objects.requireNonNull(openTelemetry, "openTelemetry");
        Objects.requireNonNull(policy, "policy");
        this.attributePolicy = policy;
        this.exceptionRecorder = Objects.requireNonNull(exceptionRecorder, "exceptionRecorder");
        this.tracer = openTelemetry.getTracer(INSTRUMENTATION_NAME);
        this.traceContextView = new DefaultTraceContextView(this::currentTraceId, this::currentSpanId);
    }

    /**
     * Runtime kill-switch preserved from Slice 1B {@code facadeEnabled} semantics.
     */
    public void setKillSwitchEnabled(boolean enabled) {
        killSwitchEnabled.set(enabled);
    }

    public boolean isKillSwitchEnabled() {
        return killSwitchEnabled.get();
    }

    @Nonnull
    public AttributePolicy attributePolicy() {
        return attributePolicy;
    }

    @Override
    @Nonnull
    public SpanHandle startSpan(@Nonnull SpanSpec spec) {
        Objects.requireNonNull(spec, "spec");
        SpanOptions.validateTopologyLinks(spec.options().topology(), spec.options().links());
        if (!killSwitchEnabled.get()) {
            return NoOpSpanHandle.INSTANCE;
        }

        Topology topology = spec.options().topology();
        SpanBuilder builder = tracer.spanBuilder(spec.name())
                .setSpanKind(SpanKinds.toSpanKind(spec.category()))
                .setAttribute(PlatformAttributes.PLATFORM_TYPE, spec.category().value());

        if (topology == Topology.ROOT || topology == Topology.DETACHED) {
            builder.setParent(Context.root());
        }

        for (var link : spec.options().links()) {
            builder.addLink(toRemoteSpanContext(link));
        }

        var span = builder.startSpan();
        // Маркер категории: parity с legacy builder'ами для marker-based enrich (PR-2b).
        Context ctx = Context.current()
                .with(span)
                .with(PlatformSpanContextKeys.PLATFORM_SPAN_CATEGORY, spec.category());
        Scope scope = ctx.makeCurrent();
        var spanScope = new OwningSpanScope(span, scope, exceptionRecorder);
        applySpecAttributes(spanScope, spec.attributes());
        return SpanHandleImpl.wrap(spanScope);
    }

    @Override
    @Nonnull
    public TraceContextView currentTraceContext() {
        return traceContextView;
    }

    @Override
    public void recordException(@Nonnull SpanHandle span, @Nullable Throwable throwable) {
        Objects.requireNonNull(span, "span").recordException(throwable);
    }

    @Override
    @Nonnull
    public TracingState state() {
        if (!killSwitchEnabled.get()) {
            return ImmutableTracingState.of(
                    TracingMode.DISABLED_BY_CONFIGURATION,
                    Optional.of("runtime.kill-switch"),
                    Map.of("source", "setFacadeEnabled(false)"));
        }
        return ImmutableTracingState.enabled();
    }

    @Nonnull
    Optional<String> currentTraceId() {
        SpanContext context = Span.current().getSpanContext();
        return context.isValid() ? Optional.of(context.getTraceId()) : Optional.empty();
    }

    @Nonnull
    Optional<String> currentSpanId() {
        SpanContext context = Span.current().getSpanContext();
        return context.isValid() ? Optional.of(context.getSpanId()) : Optional.empty();
    }

    private void applySpecAttributes(@Nonnull space.br1440.platform.tracing.api.span.SpanScope scope,
                                       @Nonnull Map<String, SpanAttributeValue> attributes) {
        for (Map.Entry<String, SpanAttributeValue> entry : attributes.entrySet()) {
            applyAttribute(scope, entry.getKey(), entry.getValue());
        }
    }

    private void applyAttribute(@Nonnull space.br1440.platform.tracing.api.span.SpanScope scope,
                                @Nonnull String key,
                                @Nonnull SpanAttributeValue value) {
        switch (value) {
            case SpanAttributeValue.StringValue sv -> scope.setAttribute(key, sv.value());
            case SpanAttributeValue.LongValue lv -> scope.setAttribute(key, lv.value());
            case SpanAttributeValue.DoubleValue dv -> scope.setAttribute(key, dv.value());
            case SpanAttributeValue.BooleanValue bv -> scope.setAttribute(key, bv.value());
            case SpanAttributeValue.StringListValue slv -> scope.setAttribute(key, String.join(",", slv.values()));
            case SpanAttributeValue.LongListValue llv -> scope.setAttribute(key, llv.values().toString());
            case SpanAttributeValue.DoubleListValue dlv -> scope.setAttribute(key, dlv.values().toString());
            case SpanAttributeValue.BooleanListValue blv -> scope.setAttribute(key, blv.values().toString());
        }
    }

    @Nonnull
    private static SpanContext toRemoteSpanContext(@Nonnull space.br1440.platform.tracing.api.span.SpanLinkContext link) {
        return SpanContext.createFromRemoteParent(
                link.traceId(),
                link.spanId(),
                TraceFlags.fromByte(link.traceFlags()),
                resolveTraceState(link.traceState()));
    }

    @Nonnull
    private static TraceState resolveTraceState(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return TraceState.getDefault();
        }
        TraceStateBuilder builder = TraceState.builder();
        for (String entry : raw.split(",")) {
            String trimmed = entry.trim();
            int separator = trimmed.indexOf('=');
            if (separator > 0 && separator < trimmed.length() - 1) {
                builder.put(trimmed.substring(0, separator).trim(), trimmed.substring(separator + 1).trim());
            }
        }
        return builder.build();
    }
}
