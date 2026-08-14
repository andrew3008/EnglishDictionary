package space.br1440.platform.tracing.otel.javaagent.control;

import java.util.List;
import java.util.Map;
import java.util.Set;

import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicySnapshot;
import space.br1440.platform.tracing.otel.sampling.properties.SamplingPolicyProperties;
import space.br1440.platform.tracing.otel.sampling.properties.SamplingPolicySnapshotFactory;

public final class CategoryBSamplingAccess {

    private CategoryBSamplingAccess() {
    }

    public static SamplingPolicySnapshot snapshot(List<String> dropPaths, Set<String> forceRecordValues) {
        return SamplingPolicySnapshotFactory.create(new SamplingPolicyProperties(
                true,
                1.0d,
                dropPaths,
                forceRecordValues,
                Map.of()));
    }

    public static SamplingPolicySnapshot snapshot(List<String> dropPaths, List<String> forceRecordValues) {
        return snapshot(dropPaths, Set.copyOf(forceRecordValues));
    }
}
