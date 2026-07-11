package space.br1440.platform.tracing.api.manual;

import jakarta.annotation.Nonnull;

/**
 * Семантический построитель Database под {@link TransportTracing#database()}.
 */
public interface DatabaseSpanBuilder extends PlatformSpanBuilder<DatabaseSpanBuilder> {

    @Nonnull
    DatabaseSpanBuilder system(@Nonnull String dbSystem);

    @Nonnull
    DatabaseSpanBuilder operation(@Nonnull String operation);

    @Nonnull
    DatabaseSpanBuilder collection(@Nonnull String collection);
}
