package space.br1440.platform.tracing.core.runtime.otel.context;

import io.opentelemetry.context.ContextKey;

import space.br1440.platform.tracing.api.span.SpanCategory;

/**
 * Internal-ключи OpenTelemetry {@code Context} платформенного semantic-слоя (core, НЕ public
 * extension API платформы).
 * <p>
 * Класс объявлен {@code public}, так как используется из {@code core.runtime.otel} (builder'ы,
 * {@code OtelTracingRuntime}) и {@code core.enrichment} ({@code SpanEnricher}) — двух разных
 * пакетов внутри {@code platform-tracing-core}, между которыми нет общего package-private
 * доступа. Несмотря на видимость {@code public}, класс не входит в публичный extension API
 * платформы и не должен использоваться за пределами {@code platform-tracing-core}.
 */
public final class PlatformSpanContextKeys {

    private PlatformSpanContextKeys() {
        // utility-класс
    }

    /**
     * Маркер платформенного span'а: проставляется {@code OtelTracingRuntime.startSpan()} при
     * создании span'а и используется {@code SpanEnricher.enrichCurrentSpanIfPlatformCategory} для
     * marker-based enrich (v3). Агентский span этот маркер НИКОГДА не несёт (другой
     * classloader/Context).
     */
    public static final ContextKey<SpanCategory> PLATFORM_SPAN_CATEGORY = ContextKey.named("platform-span-category");
}
