package space.br1440.platform.tracing.e2e.extension.jmx.wire;

import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;

/**
 * Test-only OTel extension provider that registers {@link WireRoundTripTestMBean} when the
 * dedicated test-only extension JAR is loaded via {@code -Dotel.javaagent.extensions}.
 * <p>
 * Runs in the Agent {@code ExtensionClassLoader}, which is exactly the classloader fidelity the
 * E2E test asserts (App CL builds the Map payload, invokes through JMX, the MBean resolves and
 * validates inside the Agent CL).
 * <p>
 * Registration is <b>unconditional</b>: the whole extension JAR is test-only and is loaded only by
 * E2E, so no runtime property gate is required. This replaces the former production bootstrap call
 * {@code TracingControlWireSpikeProbe.registerIfEnabled()} and the removed runtime gate
 * {@code platform.tracing.spike.jmx.wire}.
 */
public final class WireRoundTripTestExtensionProvider implements AutoConfigurationCustomizerProvider {

    @Override
    public void customize(AutoConfigurationCustomizer customizer) {
        customizer.addTracerProviderCustomizer((builder, config) -> {
            WireRoundTripTestMBeanImpl.registerSafely();
            return builder;
        });
    }
}
