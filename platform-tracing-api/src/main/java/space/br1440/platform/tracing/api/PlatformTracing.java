package space.br1440.platform.tracing.api;

import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.manual.ManualTracing;
import space.br1440.platform.tracing.api.manual.TraceContextView;

/**
 * Narrow public facade for platform manual tracing (v3 cutover, Slice 1B).
 * <p>
 * Application code obtains read-only context via {@link #traceContext()} and creates governed
 * manual spans via {@link #manual()}. Implementation details live in {@code platform-tracing-core};
 * the bean is wired through {@code platform-tracing-spring-boot-autoconfigure}.
 */
public interface PlatformTracing {

    @Nonnull
    TraceContextView traceContext();

    @Nonnull
    ManualTracing manual();

}
