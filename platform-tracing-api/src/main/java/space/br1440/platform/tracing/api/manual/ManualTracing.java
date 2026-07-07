package space.br1440.platform.tracing.api.manual;

import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.api.span.spec.SpecifiedSpan;

/**
 * Entry point for platform-governed manual tracing ({@code PlatformTracing.manual()} in v3 cutover).
 */
public interface ManualTracing {

    @Nonnull
    OperationSpanBuilder operation(@Nonnull String name);

    @Nonnull
    TransportTracing transport();

    @Nonnull
    SpecifiedSpan spanFromSpec(@Nonnull SpanSpec spec);

}
