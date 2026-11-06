package space.br1440.platform.tracing.otel.javaagent.configuration;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public final class BaggageKeyPolicy {

    private final Set<String> allowedKeys;
    private final List<Pattern> denyPatterns;

    public BaggageKeyPolicy(Set<String> allowedKeys, List<Pattern> denyPatterns) {
        this.allowedKeys = Set.copyOf(Objects.requireNonNull(allowedKeys, "allowedKeys"));
        this.denyPatterns = List.copyOf(Objects.requireNonNull(denyPatterns, "denyPatterns"));
    }

    public boolean allows(String key) {
        if (key == null || !allowedKeys.contains(key)) {
            return false;
        }

        for (Pattern pattern : denyPatterns) {
            if (pattern.matcher(key).find()) {
                return false;
            }
        }

        return true;
    }

    public boolean isEmpty() {
        return allowedKeys.isEmpty();
    }
}
