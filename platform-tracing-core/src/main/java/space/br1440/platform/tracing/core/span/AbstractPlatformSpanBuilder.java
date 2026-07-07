package space.br1440.platform.tracing.core.span;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import jakarta.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import space.br1440.platform.tracing.api.semconv.SemconvKeys;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.SpanScope;
import space.br1440.platform.tracing.core.exception.ExceptionRecorder;
import space.br1440.platform.tracing.core.semconv.AttributePolicy;
import space.br1440.platform.tracing.core.semconv.EagerOnlyKeys;
import space.br1440.platform.tracing.core.semconv.ValidatedAttributes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Базовый класс типизированных platform-builder'ов поверх OTel {@link Tracer}.
 * <p>
 * Накопление атрибутов, авто-{@code SpanKind}, anti-double guard (модель B) и строгий порядок
 * старта реализованы здесь; конкретные builder'ы выражают только fluent-методы и
 * {@link #category()}.
 * <p>
 * <b>Строгий порядок старта (KEY design):</b>
 * {@code validateAndNormalize -> имя из normalized -> SpanBuilder из normalized -> startSpan()}.
 * Sampling-relevant атрибуты попадают на {@code SpanBuilder} ДО {@code startSpan()} (sampler
 * видит только creation-time атрибуты). Дорогие lazy-значения и escape-hatch unsafe-атрибуты
 * применяются ПОСЛЕ старта и только если {@code span.isRecording()}.
 */
public abstract class AbstractPlatformSpanBuilder {

    private static final Logger log = LoggerFactory.getLogger(AbstractPlatformSpanBuilder.class);

    /** WARN о re-entry-деградации — один раз на категорию (без внешнего rate-limiter'а). */
    private static final Set<String> REENTRY_WARNED = ConcurrentHashMap.newKeySet();

    protected final Tracer tracer;
    protected final AttributePolicy policy;
    protected final ExceptionRecorder exceptionRecorder;

    private final AttributesBuilder accumulated = Attributes.builder();
    private final List<LazyAttr<?>> lazy = new ArrayList<>();
    private final Map<String, String> unsafe = new LinkedHashMap<>();
    private String name;
    private boolean forceNewSpan;

    protected AbstractPlatformSpanBuilder(@Nonnull Tracer tracer, @Nonnull AttributePolicy policy,
                                          @Nonnull ExceptionRecorder exceptionRecorder) {
        this.tracer = tracer;
        this.policy = policy;
        this.exceptionRecorder = exceptionRecorder;
    }

    /** Категория builder'а (определяет SpanKind, контракт и маркер). */
    protected abstract SpanCategory category();

    protected void setName(String name) {
        this.name = name;
    }

    protected <V> void putAttribute(AttributeKey<V> key, V value) {
        accumulated.put(key, value);
    }

    /**
     * Lazy-атрибут: запрещён для creation-time ключей ({@link EagerOnlyKeys}) — иначе значение,
     * вычисленное после {@code startSpan()}, не попало бы в sampling/имя.
     */
    protected <V> void putLazyAttribute(AttributeKey<V> key, Supplier<? extends V> supplier) {
        if (EagerOnlyKeys.contains(key)) {
            throw new IllegalArgumentException(
                    "Creation-time атрибут должен быть eager (lazy запрещён): " + key.getKey());
        }
        lazy.add(new LazyAttr<>(key, supplier));
    }

    /** Escape-hatch атрибут: аудит + (если не REJECTED) применение после старта span'а. */
    protected void putUnsafe(String key, String value) {
        if (policy.auditUnsafeAttribute(key) != AttributePolicy.UnsafeKeyClass.REJECTED) {
            unsafe.put(key, value);
        }
    }

    protected void markForceNewSpan() {
        this.forceNewSpan = true;
    }

    /**
     * Запускает span (или деградирует в enrich при re-entry платформы той же категории).
     */
    @Nonnull
    protected SpanScope startSpanInternal() {
        SpanCategory category = category();

        // Baseline creation-time атрибут: platform.trace.type = категория (sampling-relevant).
        // Ставим до валидации, чтобы required platform.trace.type был выполнен и в STRICT-режиме.
        accumulated.put(SemconvKeys.PLATFORM_TYPE, category.value());

        // Anti-double guard (модель B): деградируем в enrich ТОЛЬКО при собственном platform-маркере
        // той же категории (re-entry платформы). Агентский span маркера не несёт.
        SpanCategory active = Context.current().get(PlatformSpanContextKeys.PLATFORM_SPAN_CATEGORY);
        if (active == category && !forceNewSpan) {
            warnReentryOnce(category);
            return NonOwningSpanScope.enrich(
                    Span.current(), accumulated.build(), policy, category, builderName(), exceptionRecorder);
        }

        // Единый snapshot нормализованных атрибутов для имени И для SpanBuilder.
        ValidatedAttributes validated =
                policy.validateAndNormalize(category, accumulated.build(), builderName());
        String spanName = PlatformSpanNameBuilder.forCategory(category, validated.attributes(), name);

        SpanBuilder builder = tracer.spanBuilder(spanName)
                .setSpanKind(SpanKinds.toSpanKind(category))
                .setAllAttributes(validated.attributes());

        Span span = builder.startSpan();
        Context ctx = Context.current()
                .with(span)
                .with(PlatformSpanContextKeys.PLATFORM_SPAN_CATEGORY, category);
        Scope scope = ctx.makeCurrent();

        if (span.isRecording()) {
            for (LazyAttr<?> attr : lazy) {
                attr.applyTo(span);
            }
            unsafe.forEach(span::setAttribute);
        }
        return new OwningSpanScope(span, scope, exceptionRecorder);
    }

    private String builderName() {
        return getClass().getSimpleName();
    }

    private void warnReentryOnce(SpanCategory category) {
        if (REENTRY_WARNED.add(category.name())) {
            log.warn("re-entry платформы категории {} -> enrich текущего span'а (новый span не создаётся; "
                    + "используйте forceNewSpan() для явного нового span'а)", category);
        }
    }

    /** Lazy-атрибут: ключ + поставщик значения. */
    private record LazyAttr<V>(AttributeKey<V> key, Supplier<? extends V> supplier) {
        void applyTo(Span span) {
            V value = supplier.get();
            if (value != null) {
                span.setAttribute(key, value);
            }
        }
    }
}
