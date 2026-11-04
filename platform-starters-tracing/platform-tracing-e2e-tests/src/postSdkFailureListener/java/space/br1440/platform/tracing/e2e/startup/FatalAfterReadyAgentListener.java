package space.br1440.platform.tracing.e2e.startup;

import io.opentelemetry.javaagent.extension.AgentListener;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;

@SuppressWarnings("unused")
public final class FatalAfterReadyAgentListener implements AgentListener {

    public static final String THROWN_MARKER = "FATAL_AFTER_READY_LISTENER_THROWN";

    // Порядок выше платформенного ActivationAgentListener (100): отказ возникает после публикации READY.
    private static final int ORDER_AFTER_PLATFORM_ACTIVATION = 200;

    @Override
    public void afterAgent(AutoConfiguredOpenTelemetrySdk autoConfiguredSdk) {
        PostReadyLinkageFailure failure = new PostReadyLinkageFailure();
        // Системные свойства — единственное хранилище, общее для extension и application classloader.
        System.getProperties().put(PostReadyLinkageFailure.THROWN_PROPERTY, failure);
        System.out.println(THROWN_MARKER);
        throw failure;
    }

    @Override
    public int order() {
        return ORDER_AFTER_PLATFORM_ACTIVATION;
    }
}
