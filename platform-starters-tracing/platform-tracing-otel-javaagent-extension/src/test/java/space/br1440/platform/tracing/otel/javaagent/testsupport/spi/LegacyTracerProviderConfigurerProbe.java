package space.br1440.platform.tracing.otel.javaagent.testsupport.spi;

public final class LegacyTracerProviderConfigurerProbe {

    public LegacyTracerProviderConfigurerProbe() {
        TracePipelineAttackProbe.LEGACY_TRACER_CONFIGURER_CONSTRUCTED.set(true);
    }
}
