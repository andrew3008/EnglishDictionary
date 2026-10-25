package space.br1440.platform.tracing.runtime.sampling.properties;

import java.util.List;
import java.util.Set;

import space.br1440.platform.tracing.runtime.sampling.model.SamplingPolicySnapshot;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class SamplingPolicySnapshotFactory {

    public static SamplingPolicySnapshot create(List<String> droppedRoutes, List<String> forceRecordValues) {
        SamplingPolicyPropertiesValidator.validate(droppedRoutes, forceRecordValues);
        return new SamplingPolicySnapshot(
                true,
                droppedRoutes,
                Set.copyOf(forceRecordValues),
                List.of(),
                1.0d
        );
    }
}
