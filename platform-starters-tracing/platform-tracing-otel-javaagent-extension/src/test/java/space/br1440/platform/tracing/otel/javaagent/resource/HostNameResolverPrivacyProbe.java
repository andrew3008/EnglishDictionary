package space.br1440.platform.tracing.otel.javaagent.resource;

import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLoggingRuntime;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class HostNameResolverPrivacyProbe {

    public static void main(String[] args) {
        String secret = requiredSecret(args);
        AgentLoggingRuntime.shared().applyLevel(AgentLogLevel.TRACE);
        HostNameResolver.resolveLocalHostNameWithTimeout(() -> {
            RuntimeException failure = new RuntimeException(secret);
            failure.addSuppressed(new IllegalStateException(secret));
            throw failure;
        });
    }

    private static String requiredSecret(String[] args) {
        if (args.length != 1 || args[0].isBlank()) {
            throw new IllegalArgumentException("Exactly one non-blank secret marker is required");
        }
        return args[0];
    }
}
