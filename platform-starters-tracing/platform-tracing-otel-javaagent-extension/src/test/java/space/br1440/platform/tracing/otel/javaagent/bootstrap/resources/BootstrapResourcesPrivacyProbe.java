package space.br1440.platform.tracing.otel.javaagent.bootstrap.resources;

import java.util.concurrent.atomic.AtomicReference;

import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;
import space.br1440.platform.tracing.otel.javaagent.logging.event.BootstrapLogEvent;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class BootstrapResourcesPrivacyProbe {

    public static void main(String[] args) {
        String secret = requiredSecret(args);
        AtomicReference<Runnable> shutdownAction = new AtomicReference<>();
        try (BootstrapResources resources = new BootstrapResources((threadName, action) -> {
            shutdownAction.set(action);
            return () -> {
            };
        })) {
            resources.installShutdownHook("privacy-canary-shutdown", cleanupFailure -> {
                throw new IllegalStateException(secret);
            });
            shutdownAction.get().run();
        }

        PlatformAgentLogger.get(BootstrapResourcesPrivacyProbe.class)
                .log(BootstrapLogEvent.EXTENSION_INSTALLED);
    }

    private static String requiredSecret(String[] args) {
        if (args.length != 1 || args[0].isBlank()) {
            throw new IllegalArgumentException("Exactly one non-blank secret marker is required");
        }
        return args[0];
    }
}
