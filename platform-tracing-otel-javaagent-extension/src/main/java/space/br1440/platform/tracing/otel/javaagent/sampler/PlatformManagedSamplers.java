package space.br1440.platform.tracing.otel.javaagent.sampler;

import io.opentelemetry.sdk.trace.samplers.Sampler;
import lombok.experimental.UtilityClass;

@UtilityClass
public final class PlatformManagedSamplers {

    static final String DESCRIPTION_MARKER = "PlatformRuleBasedSampler";

    public static CompositeSampler findComposite(Sampler sampler) {
        if (sampler instanceof PlatformManagedSampler managed) {
            return managed.platformCompositeSampler();
        }

        return null;
    }

    public static boolean isPlatformManaged(Sampler sampler) {
        if (sampler == null) {
            return false;
        }

        if (sampler instanceof PlatformManagedSampler) {
            return true;
        }

        try {
            String description = sampler.getDescription();
            return (description != null) && description.contains(DESCRIPTION_MARKER);
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
