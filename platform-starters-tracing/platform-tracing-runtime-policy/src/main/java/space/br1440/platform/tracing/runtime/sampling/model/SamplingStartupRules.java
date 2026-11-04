package space.br1440.platform.tracing.runtime.sampling.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import space.br1440.platform.common.util.StringUtils;

public final class SamplingStartupRules {

    public static final int MAX_DROP_PATHS = 100;
    public static final int MAX_FORCE_VALUES = 50;
    public static final int MAX_FORCE_VALUE_LENGTH = 255;

    private final List<String> droppedRoutes;
    private final Set<String> forceRecordValues;

    private SamplingStartupRules(List<String> droppedRoutes, Set<String> forceRecordValues) {
        this.droppedRoutes = List.copyOf(droppedRoutes);
        this.forceRecordValues = Set.copyOf(forceRecordValues);
    }

    public static SamplingStartupRules create(List<String> droppedRoutes, List<String> forceRecordValues) {
        Objects.requireNonNull(droppedRoutes, "droppedRoutes");
        Objects.requireNonNull(forceRecordValues, "forceRecordValues");
        if (droppedRoutes.size() > MAX_DROP_PATHS) {
            throw new IllegalArgumentException("Too many drop paths configured");
        }

        if (forceRecordValues.size() > MAX_FORCE_VALUES) {
            throw new IllegalArgumentException("Too many force record values");
        }

        List<String> paths = new ArrayList<>(droppedRoutes.size());
        for (String path : droppedRoutes) {
            if (StringUtils.isNullOrBlank(path)) {
                throw new IllegalArgumentException("Drop path must not be null or blank");
            }

            String normalized = path.trim();
            if (!normalized.startsWith("/")) {
                throw new IllegalArgumentException("Drop path must start with /");
            }

            paths.add(normalized);
        }

        Set<String> values = new HashSet<>();
        for (String value : forceRecordValues) {
            if (StringUtils.isNullOrBlank(value)) {
                throw new IllegalArgumentException("Force record value must not be null or blank");
            }

            String normalized = value.trim();
            if (normalized.length() > MAX_FORCE_VALUE_LENGTH) {
                throw new IllegalArgumentException("Force record value too long");
            }

            values.add(normalized.toLowerCase(Locale.ROOT));
        }

        return new SamplingStartupRules(paths, values);
    }

    public List<String> droppedRoutes() {
        return droppedRoutes;
    }

    public Set<String> forceRecordValues() {
        return forceRecordValues;
    }
}
