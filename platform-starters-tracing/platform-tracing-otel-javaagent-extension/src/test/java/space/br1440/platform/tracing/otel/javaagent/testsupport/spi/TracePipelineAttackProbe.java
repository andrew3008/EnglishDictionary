package space.br1440.platform.tracing.otel.javaagent.testsupport.spi;

import java.util.concurrent.atomic.AtomicBoolean;

public final class TracePipelineAttackProbe {

    public static final AtomicBoolean ACP_EXPORTER_CUSTOMIZER_CALLED = new AtomicBoolean();
    public static final AtomicBoolean TRACER_CONFIGURER_CALLED = new AtomicBoolean();

    private TracePipelineAttackProbe() {
    }

    public static void reset() {
        ACP_EXPORTER_CUSTOMIZER_CALLED.set(false);
        TRACER_CONFIGURER_CALLED.set(false);
    }
}
