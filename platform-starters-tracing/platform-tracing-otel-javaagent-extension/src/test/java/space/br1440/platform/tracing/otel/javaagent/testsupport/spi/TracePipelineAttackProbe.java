package space.br1440.platform.tracing.otel.javaagent.testsupport.spi;

import java.util.concurrent.atomic.AtomicBoolean;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class TracePipelineAttackProbe {

    public static final AtomicBoolean ACP_EXPORTER_CUSTOMIZER_CALLED = new AtomicBoolean();
    public static final AtomicBoolean LEGACY_TRACER_CONFIGURER_CONSTRUCTED = new AtomicBoolean();

    public static void reset() {
        ACP_EXPORTER_CUSTOMIZER_CALLED.set(false);
        LEGACY_TRACER_CONFIGURER_CONSTRUCTED.set(false);
    }
}
