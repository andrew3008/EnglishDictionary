package space.br1440.platform.tracing.otel.javaagent.propagation;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import jakarta.annotation.Nonnull;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.baggage.BaggageBuilder;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;

/**
 * Filters outbound baggage on {@link #inject} against an allowlist and deny-pattern set.
 * <p>
 * {@link #extract} is an intentional unfiltered passthrough to the delegate: inbound baggage is
 * not pruned here. This is a deliberate, tested boundary (see
 * {@code FilteringBaggagePropagatorTest#extract_isUnfilteredPassthrough_byDesign}), not an
 * oversight — nothing downstream trusts unfiltered inbound {@code Baggage} for anything other
 * than the span-processor plane, which applies its own independent allowlist
 * ({@code platform.tracing.baggage.processor.*}) before writing span attributes. Property names
 * that reach this filter (e.g. {@code platform.tracing.baggage.propagation.outbound-*}) say
 * "outbound" precisely because this asymmetry exists.
 */
final class FilteringBaggagePropagator implements TextMapPropagator {

    private final TextMapPropagator delegate;
    private final Set<String> allowlist;
    private final List<Pattern> denyPatterns;

    FilteringBaggagePropagator(TextMapPropagator delegate, Set<String> allowlist, List<String> denyPatternStrings) {
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
    public <C> Context extract(@Nonnull Context context, C carrier, @Nonnull TextMapGetter<C> getter) {
        return delegate.extract(context, carrier, getter);
    }

    @Override
    public <C> void inject(@Nonnull Context context, C carrier, @Nonnull TextMapSetter<C> setter) {
        Baggage source = Baggage.fromContext(context);
        if (source.isEmpty()) {
            return;
        }

        Baggage filtered = filterAllowed(source);
        if (filtered.isEmpty()) {
            return;
        }

        delegate.inject(filtered.storeInContext(context), carrier, setter);
    }

    private Baggage filterAllowed(Baggage source) {
        BaggageBuilder builder = Baggage.builder();
        source.forEach((key, entry) -> {
            if (isAllowed(key)) {
                builder.put(key, entry.getValue(), entry.getMetadata());
            }
        });

        return builder.build();
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
