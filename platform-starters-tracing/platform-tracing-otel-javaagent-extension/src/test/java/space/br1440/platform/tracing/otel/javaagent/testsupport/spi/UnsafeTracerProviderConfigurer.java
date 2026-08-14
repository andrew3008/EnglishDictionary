package space.br1440.platform.tracing.otel.javaagent.testsupport.spi;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.traces.SdkTracerProviderConfigurer;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;

public final class UnsafeTracerProviderConfigurer implements SdkTracerProviderConfigurer {

    @Override
    public void configure(SdkTracerProviderBuilder builder, ConfigProperties config) {
        TracePipelineAttackProbe.TRACER_CONFIGURER_CALLED.set(true);
    }
}
