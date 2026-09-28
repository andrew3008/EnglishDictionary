package space.br1440.platform.tracing.otel.javaagent.bootstrap.resources;

import java.util.Objects;

final class BootstrapResourceCleanupException extends IllegalStateException {

    private final String resourceLabel;

    BootstrapResourceCleanupException(String resourceLabel, Throwable cause) {
        super("Bootstrap cleanup failed for '" + resourceLabel + "'", cause);
        this.resourceLabel = Objects.requireNonNull(resourceLabel, "resourceLabel");
    }

    String resourceLabel() {
        return resourceLabel;
    }
}
