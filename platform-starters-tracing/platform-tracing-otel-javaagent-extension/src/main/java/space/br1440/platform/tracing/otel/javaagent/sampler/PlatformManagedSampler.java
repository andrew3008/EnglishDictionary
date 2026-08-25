package space.br1440.platform.tracing.otel.javaagent.sampler;

public interface PlatformManagedSampler {

    CompositeSampler platformCompositeSampler();

}
