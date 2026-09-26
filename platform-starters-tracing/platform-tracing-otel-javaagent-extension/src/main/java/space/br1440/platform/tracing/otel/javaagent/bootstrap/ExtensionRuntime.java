package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import lombok.experimental.UtilityClass;

@UtilityClass
final class ExtensionRuntime {

    private static final BootstrapCoordinator BOOTSTRAP = BootstrapCoordinator.createDefault();

    static BootstrapCoordinator bootstrap() {
        return BOOTSTRAP;
    }
}
