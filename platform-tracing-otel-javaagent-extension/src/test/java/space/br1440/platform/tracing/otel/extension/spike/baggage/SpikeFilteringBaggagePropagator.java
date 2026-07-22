package space.br1440.platform.tracing.otel.extension.spike.baggage;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Spike-only wrapper: inject только allowlisted baggage keys; extract делегируется (если есть delegate).
 * <p>
 * Не production-код — используется в {@link BaggageFilteringSpikeTest} для выбора стратегии PR-2.
 */
public final class SpikeFilteringBaggagePropagator implements TextMapPropagator {

    public static final String BAGGAGE_HEADER = "baggage";

    private final TextMapPropagator delegate;
    private final Set<String> allowlist;
    private final List<Pattern> denyPatterns;

    public SpikeFilteringBaggagePropagator(TextMapPropagator delegate,
                                           Set<String> allowlist,
                                           List<String> denyPatternStrings) {
        this.delegate = delegate;
        this.allowlist = allowlist.stream()
                .map(k -> k.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        this.denyPatterns = denyPatternStrings.stream()
                .map(p -> Pattern.compile(p, Pattern.CASE_INSENSITIVE))
                .toList();
    }

    /** Только фильтрующий inject без extract (append к composite). */
    public SpikeFilteringBaggagePropagator(Set<String> allowlist, List<String> denyPatternStrings) {
        this(null, allowlist, denyPatternStrings);
    }

    @Override
    public Collection<String> fields() {
        return List.of(BAGGAGE_HEADER);
    }

    @Override
    public <C> Context extract(Context context, C carrier, TextMapGetter<C> getter) {
        if (delegate != null) {
            return delegate.extract(context, carrier, getter);
        }
        return context;
    }

    @Override
    public <C> void inject(Context context, C carrier, TextMapSetter<C> setter) {
        if (context == null || setter == null) {
            return;
        }
        Baggage baggage = Baggage.fromContext(context);
        if (baggage.isEmpty()) {
            return;
        }
        String filtered = buildFilteredHeader(baggage);
        if (filtered.isEmpty()) {
            // Явно перезаписываем пустым — убираем PII, если stock baggage уже писал header.
            setter.set(carrier, BAGGAGE_HEADER, "");
            return;
        }
        setter.set(carrier, BAGGAGE_HEADER, filtered);
    }

    private String buildFilteredHeader(Baggage baggage) {
        StringBuilder sb = new StringBuilder();
        baggage.forEach((key, entry) -> {
            if (!isAllowed(key)) {
                return;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(key).append('=').append(entry.getValue());
        });
        return sb.toString();
    }

    private boolean isAllowed(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.toLowerCase(Locale.ROOT);
        if (!allowlist.contains(normalized)) {
            return false;
        }
        for (Pattern deny : denyPatterns) {
            if (deny.matcher(normalized).find()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Пытается извлечь вложенные propagator'ы из {@code MultiTextMapPropagator} через reflection.
     */
    public static List<TextMapPropagator> unwrapPropagators(TextMapPropagator root) {
        if (root == null) {
            return List.of();
        }
        if (!root.getClass().getName().endsWith("MultiTextMapPropagator")) {
            return List.of(root);
        }
        try {
            var field = root.getClass().getDeclaredField("textMapPropagators");
            field.setAccessible(true);
            TextMapPropagator[] arr = (TextMapPropagator[]) field.get(root);
            List<TextMapPropagator> result = new java.util.ArrayList<>();
            for (TextMapPropagator p : arr) {
                result.addAll(unwrapPropagators(p));
            }
            return result;
        } catch (ReflectiveOperationException e) {
            return List.of(root);
        }
    }

    /**
     * Пересобирает composite без W3CBaggagePropagator, с фильтрующим wrapper на его месте.
     */
    public static TextMapPropagator replaceBaggageWithFilter(TextMapPropagator existing,
                                                             Set<String> allowlist,
                                                             List<String> denyPatterns) {
        List<TextMapPropagator> parts = new java.util.ArrayList<>();
        for (TextMapPropagator p : unwrapPropagators(existing)) {
            if (p.getClass().getName().contains("W3CBaggagePropagator")) {
                parts.add(new SpikeFilteringBaggagePropagator(allowlist, denyPatterns));
            } else {
                parts.add(p);
            }
        }
        if (parts.isEmpty()) {
            parts.add(new SpikeFilteringBaggagePropagator(allowlist, denyPatterns));
        }
        return TextMapPropagator.composite(parts);
    }
}
