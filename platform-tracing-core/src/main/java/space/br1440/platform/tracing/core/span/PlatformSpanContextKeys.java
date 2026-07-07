package space.br1440.platform.tracing.core.span;

import io.opentelemetry.context.ContextKey;

import space.br1440.platform.tracing.api.span.SpanCategory;

/**
 * Internal-ключи OpenTelemetry {@code Context} платформенного semantic-слоя (core, НЕ public API).
 * <p>
 * Package-private: используется builder'ами и {@code SpanEnricher} внутри пакета
 * {@code core.span}. Намеренно не публикуется — это деталь реализации anti-double guard
 * (модель B) и marker-based enrich.
 */
final class PlatformSpanContextKeys {

    private PlatformSpanContextKeys() {
        // utility-класс
    }

    /**
     * Маркер платформенного span'а: проставляется каждым platform-builder'ом при создании span'а
     * и используется (1) anti-double guard'ом — деградация в enrich при re-entry той же категории,
     * (2) marker-based enrich. Агентский span этот маркер НИКОГДА не несёт (другой
     * classloader/Context).
     */
    static final ContextKey<SpanCategory> PLATFORM_SPAN_CATEGORY = ContextKey.named("platform-span-category");
}
