package space.br1440.platform.tracing.core.span;

import io.opentelemetry.api.trace.Span;
import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.semconv.SemconvKeys;
import space.br1440.platform.tracing.api.span.SpanResult;
import space.br1440.platform.tracing.api.span.enrich.GenericEnrichScope;

import java.util.Objects;

/**
 * Реализация {@link GenericEnrichScope}: пишет ТОЛЬКО фиксированный набор platform-safe ключей.
 * <p>
 * Ключи захардкожены в typed-методах — записать произвольный {@code AttributeKey} (например
 * {@code db.statement}) через этот scope невозможно. Для {@link #businessTag} полный ключ строит
 * сам scope ({@code platform.business.<normalizedName>}), пользователь контролирует только
 * логическое имя.
 */
final class DefaultGenericEnrichScope implements GenericEnrichScope {

    /** Префикс бизнес-тегов: пользователь НЕ контролирует полный ключ. */
    private static final String BUSINESS_PREFIX = "platform.business.";

    private final Span span;

    DefaultGenericEnrichScope(@Nonnull Span span) {
        this.span = span;
    }

    @Override
    @Nonnull
    public GenericEnrichScope requestId(@Nonnull String requestId) {
        span.setAttribute(SemconvKeys.PLATFORM_REQUEST_ID, Objects.requireNonNull(requestId, "requestId"));
        return this;
    }

    @Override
    @Nonnull
    public GenericEnrichScope userHash(@Nonnull String hash) {
        span.setAttribute(SemconvKeys.PLATFORM_USER_HASH, Objects.requireNonNull(hash, "hash"));
        return this;
    }

    @Override
    @Nonnull
    public GenericEnrichScope result(@Nonnull SpanResult result) {
        span.setAttribute(SemconvKeys.PLATFORM_RESULT, Objects.requireNonNull(result, "result").value());
        return this;
    }

    @Override
    @Nonnull
    public GenericEnrichScope businessTag(@Nonnull String name, @Nonnull String value) {
        Objects.requireNonNull(value, "value");
        String normalized = normalize(name);
        if (!normalized.isEmpty()) {
            span.setAttribute(BUSINESS_PREFIX + normalized, value);
        }
        return this;
    }

    /**
     * Нормализует логическое имя бизнес-тега: lower-case, любые не-[a-z0-9] схлопываются в '_',
     * крайние '_' срезаются. Гарантирует low-cardinality и безопасный суффикс ключа.
     */
    static String normalize(String name) {
        if (name == null) {
            return "";
        }
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        StringBuilder sb = new StringBuilder(lower.length());
        boolean prevUnderscore = false;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                sb.append(c);
                prevUnderscore = false;
            } else if (!prevUnderscore && sb.length() > 0) {
                sb.append('_');
                prevUnderscore = true;
            }
        }
        int end = sb.length();
        while (end > 0 && sb.charAt(end - 1) == '_') {
            end--;
        }
        return sb.substring(0, end);
    }
}
