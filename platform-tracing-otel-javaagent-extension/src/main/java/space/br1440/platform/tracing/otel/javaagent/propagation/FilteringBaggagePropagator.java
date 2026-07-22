package space.br1440.platform.tracing.otel.javaagent.propagation;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.baggage.BaggageEntry;
import io.opentelemetry.api.baggage.BaggageEntryMetadata;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Обертка над штатным baggage-propagator'ом для фильтрации исходящих данных.
 * <p>
 * Причина: стандартный {@link io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator}
 * слепо распространяет все ключи багажа во внешние системы, что создаёт риск утечки
 * чувствительных данных (паролей, токенов, PII). Данный propagator делегирует {@code extract()}
 * штатному классу, а на {@code inject()} перехватывает управление и фильтрует ключи.
 */
public final class FilteringBaggagePropagator implements TextMapPropagator {

    public static final String BAGGAGE_HEADER = "baggage";

    private final TextMapPropagator delegate;
    private final Set<String> allowlist;
    private final List<Pattern> denyPatterns;

    public FilteringBaggagePropagator(TextMapPropagator delegate, Set<String> allowlist, List<String> denyPatternStrings) {
        this.delegate = delegate;
        this.allowlist = allowlist.stream()
                .map(k -> k.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        this.denyPatterns = denyPatternStrings.stream()
                .map(p -> Pattern.compile(p, Pattern.CASE_INSENSITIVE))
                .toList();
    }

    @Override
    public Collection<String> fields() {
        return delegate.fields();
    }

    @Override
    public <C> Context extract(Context context, C carrier, TextMapGetter<C> getter) {
        return delegate.extract(context, carrier, getter);
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
            return; // Пустой заголовок не записываем
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
            sb.append(URLEncoder.encode(key, StandardCharsets.UTF_8))
              .append('=')
              .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
              
            BaggageEntryMetadata metadata = entry.getMetadata();
            if (metadata != null && metadata.getValue() != null && !metadata.getValue().isEmpty()) {
                sb.append(';').append(metadata.getValue());
            }
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
}
