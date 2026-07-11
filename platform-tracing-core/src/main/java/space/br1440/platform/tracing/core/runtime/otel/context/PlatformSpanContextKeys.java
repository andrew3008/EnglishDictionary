package space.br1440.platform.tracing.core.runtime.otel.context;

import io.opentelemetry.context.ContextKey;

import lombok.experimental.UtilityClass;
import space.br1440.platform.tracing.api.span.SpanCategory;

/**
 * Internal-ключи OpenTelemetry {@code Context} платформенного semantic-слоя
 * (core, не public extension API платформы).
 */
@UtilityClass
public final class PlatformSpanContextKeys {

    /**
     * Маркер платформенного span'а
     */
    public static final ContextKey<SpanCategory> PLATFORM_SPAN_CATEGORY = ContextKey.named("platform-span-category");

}
