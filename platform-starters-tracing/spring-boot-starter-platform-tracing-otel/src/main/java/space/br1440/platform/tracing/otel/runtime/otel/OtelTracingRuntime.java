package space.br1440.platform.tracing.otel.runtime.otel;

import java.util.Objects;
import java.util.Optional;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import space.br1440.platform.common.util.StringUtils;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.api.context.ActiveTraceContextView;
import space.br1440.platform.tracing.api.context.CorrelationScope;
import space.br1440.platform.tracing.api.span.RemoteSpanLink;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.api.span.spec.SpanRelationship;
import space.br1440.platform.tracing.api.span.spec.SpanRelationshipSpec;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.otel.attributes.validation.SpanAttributeValidator;
import space.br1440.platform.tracing.otel.context.DefaultActiveTraceContextView;
import space.br1440.platform.tracing.otel.exception.ExceptionRecorder;
import space.br1440.platform.tracing.otel.runtime.DatabaseSpanHandleImpl;
import space.br1440.platform.tracing.otel.runtime.SpanHandleImpl;
import space.br1440.platform.tracing.otel.runtime.TraceControlBoundaryState;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;
import space.br1440.platform.tracing.otel.runtime.otel.scope.OwningSpanScope;
import space.br1440.platform.tracing.otel.runtime.state.ImmutableTracingState;
import space.br1440.platform.tracing.otel.runtime.state.TracingState;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.TraceStateBuilder;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;

public final class OtelTracingRuntime implements TracingRuntime {

    public static final String INSTRUMENTATION_NAME = "space.br1440.platform.tracing";

    private final Tracer tracer;
    private final SpanAttributeValidator attributePolicy;
    private final ExceptionRecorder exceptionRecorder;
    private final OtelIdentityStorage identityStorage = new OtelIdentityStorage();
    private final ActiveTraceContextView traceContextView;

    public OtelTracingRuntime(@Nonnull OpenTelemetry openTelemetry,
                              @Nonnull SpanAttributeValidator policy,
                              @Nonnull ExceptionRecorder exceptionRecorder) {
        Objects.requireNonNull(openTelemetry, "openTelemetry");
        Objects.requireNonNull(policy, "policy");

        this.attributePolicy = policy;
        this.exceptionRecorder = Objects.requireNonNull(exceptionRecorder, "exceptionRecorder");
        this.tracer = openTelemetry.getTracer(INSTRUMENTATION_NAME);
        this.traceContextView = new DefaultActiveTraceContextView(
                this::currentTraceId,
                this::currentSpanId,
                this::currentRequestId,
                this::currentCorrelationId,
                this::currentTraceparent
        );
    }

    @Override
    @Nonnull
    public SpanAttributeValidator spanAttributeValidator() {
        return attributePolicy;
    }

    @Override
    @Nonnull
    public SpanHandle startSpan(@Nonnull SpanSpec spec) {
        Objects.requireNonNull(spec, "spec");
        SpanRelationshipSpec.validateRelationshipLinks(spec.relationship().kind(), spec.relationship().links());

        SpanRelationship relationship = spec.relationship().kind();
        SpanBuilder builder = tracer.spanBuilder(spec.name())
                .setSpanKind(SpanKinds.toSpanKind(spec.category()));
        builder.setAllAttributes(SpanSpecAttributeValueConverter.toAttributes(spec.attributes()));

        builder.setAttribute(PlatformAttributes.PLATFORM_TYPE, spec.category().value());
        currentCorrelationId()
                .ifPresent(value -> builder.setAttribute(PlatformAttributes.PLATFORM_CORRELATION_ID, value));

        if (relationship == SpanRelationship.ROOT || relationship == SpanRelationship.DETACHED) {
            builder.setParent(Context.root());
        }

        for (RemoteSpanLink link : spec.relationship().links()) {
            builder.addLink(toRemoteSpanContext(link));
        }

        Span span = builder.startSpan();
        OwningSpanScope scope = OwningSpanScope.activate(span, exceptionRecorder);
        if (spec.category() == SpanCategory.DATABASE) {
            return DatabaseSpanHandleImpl.wrap(scope);
        }

        return SpanHandleImpl.wrap(scope);
    }

    @Override
    @Nonnull
    public ActiveTraceContextView currentTraceContext() {
        return traceContextView;
    }

    @Override
    @Nonnull
    public CorrelationScope openCorrelationScope(@Nonnull String correlationId) {
        return identityStorage.openCorrelationScope(correlationId);
    }

    @Override
    @Nonnull
    public CorrelationScope openRequestIdentityScope(@Nonnull String requestId, boolean forceTrace, boolean qaTrace) {
        return identityStorage.openRequestScope(requestId, forceTrace, qaTrace);
    }

    @Override
    @Nonnull
    public String requireCanonicalCorrelationId(@Nonnull String correlationId) {
        return identityStorage.requireCanonicalCorrelationId(correlationId);
    }

    @Override
    @Nonnull
    public Optional<String> currentRequestId() {
        return identityStorage.requestId();
    }

    @Override
    @Nonnull
    public Optional<String> currentCorrelationId() {
        return identityStorage.correlationId();
    }

    @Override
    @Nonnull
    public TraceControlBoundaryState currentTraceControlBoundary() {
        return identityStorage.traceControlBoundary();
    }


    @Override
    public void recordException(@Nonnull SpanHandle span, @Nullable Throwable throwable) {
        Objects.requireNonNull(span, "span").recordException(throwable);
    }

    @Override
    @Nonnull
    public TracingState state() {
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

    @Nonnull
    Optional<String> currentTraceparent() {
        SpanContext context = Span.current().getSpanContext();
        if (!context.isValid()) {
            return Optional.empty();
        }

        String traceparent = "00-" + context.getTraceId() + "-" + context.getSpanId() + "-" + context.getTraceFlags().asHex();
        return Optional.of(traceparent);
    }

    @Nonnull
    private static SpanContext toRemoteSpanContext(@Nonnull RemoteSpanLink link) {
        return SpanContext.createFromRemoteParent(
                link.traceId(),
                link.spanId(),
                TraceFlags.fromByte(link.traceFlags()),
                resolveTraceState(link.traceState())
        );
    }

    @Nonnull
    private static TraceState resolveTraceState(@Nullable String raw) {
        if (StringUtils.isNullOrBlank(raw)) {
            return TraceState.getDefault();
        }

        TraceStateBuilder builder = TraceState.builder();
        for (String entry : raw.split(",")) {
            String trimmed = entry.trim();
            int separator = trimmed.indexOf('=');
            if ((separator > 0) && (separator < trimmed.length() - 1)) {
                builder.put(trimmed.substring(0, separator).trim(), trimmed.substring(separator + 1).trim());
            }
        }

        return builder.build();
    }
}
