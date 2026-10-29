package space.br1440.platform.tracing.otel.javaagent.configuration;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class BaggageKeyPolicy {

    private final Set<String> allowedKeys;
    private final List<Pattern> denyPatterns;

    public BaggageKeyPolicy(Set<String> allowedKeys, List<Pattern> denyPatterns) {
        this.allowedKeys = allowedKeys.stream()
                .map(key -> key.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        this.denyPatterns = List.copyOf(denyPatterns);
    }

    public boolean allows(String key) {
        if (key == null) {
            return false;
        }

        String normalizedKey = key.toLowerCase(Locale.ROOT);
        if (!allowedKeys.contains(normalizedKey)) {
            return false;
        }

        for (Pattern pattern : denyPatterns) {
            if (pattern.matcher(normalizedKey).find()) {
                return false;
            }
        }

        return true;
    }

    public boolean isEmpty() {
        return allowedKeys.isEmpty();
    }
}
