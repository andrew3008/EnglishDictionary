package space.br1440.platform.tracing.otel.javaagent.control;

import java.util.List;

import space.br1440.platform.tracing.runtime.sampling.model.SamplingPolicySnapshot;
import space.br1440.platform.tracing.runtime.sampling.properties.SamplingPolicySnapshotFactory;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class CategoryBSamplingAccess {

    public static SamplingPolicySnapshot snapshot(List<String> dropPaths, List<String> forceRecordValues) {
        return SamplingPolicySnapshotFactory.create(dropPaths, forceRecordValues);
    }
}
