package space.br1440.platform.tracing.otel.javaagent.control;

import java.util.List;

import space.br1440.platform.tracing.runtime.sampling.model.SamplingStartupRules;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class CategoryBSamplingAccess {

    public static SamplingStartupRules snapshot(List<String> dropPaths, List<String> forceRecordValues) {
        return SamplingStartupRules.create(dropPaths, forceRecordValues);
    }
}
