package space.br1440.platform.tracing.api.manual;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.semconv.annotation.DatabaseSemconvVersion;

/**
 * Семантический построитель Database под {@link TransportTracing#database()}.
 */
@DatabaseSemconvVersion("1.28.0")
public interface DatabaseSpanBuilder extends ManualSpanBuilder<DatabaseSpanBuilder> {

    @Nonnull
    DatabaseSpanBuilder system(@Nonnull String dbSystem);

    @Nonnull
    DatabaseSpanBuilder operation(@Nonnull String operation);

    @Nonnull
    DatabaseSpanBuilder collection(@Nonnull String collection);
}
