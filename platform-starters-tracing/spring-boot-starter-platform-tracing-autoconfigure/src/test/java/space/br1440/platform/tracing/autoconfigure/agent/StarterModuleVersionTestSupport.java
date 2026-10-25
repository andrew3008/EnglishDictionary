package space.br1440.platform.tracing.autoconfigure.agent;

public final class StarterModuleVersionTestSupport {

    private StarterModuleVersionTestSupport() {
    }

    public static String current() {
        return StarterModuleVersionLoader.starterModuleVersion();
    }
}
