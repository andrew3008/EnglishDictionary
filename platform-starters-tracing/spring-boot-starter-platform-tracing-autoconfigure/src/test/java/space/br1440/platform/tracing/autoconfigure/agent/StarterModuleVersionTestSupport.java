package space.br1440.platform.tracing.autoconfigure.agent;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class StarterModuleVersionTestSupport {

    public static String current() {
        return StarterModuleVersionLoader.starterModuleVersion();
    }
}
