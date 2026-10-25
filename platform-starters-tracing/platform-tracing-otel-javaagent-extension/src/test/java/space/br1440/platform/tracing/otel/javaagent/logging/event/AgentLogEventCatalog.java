package space.br1440.platform.tracing.otel.javaagent.logging.event;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class AgentLogEventCatalog {

    private static final List<AgentLogEventType> ALL =
            Arrays.stream(AgentLogEventType.class.getPermittedSubclasses())
                    .flatMap(AgentLogEventCatalog::enumConstants)
                    .toList();

    public static List<AgentLogEventType> all() {
        return ALL;
    }

    private static Stream<AgentLogEventType> enumConstants(Class<?> permittedType) {
        Object[] constants = permittedType.getEnumConstants();
        if (constants == null) {
            throw new IllegalStateException(
                    "Permitted AgentLogEventType must be an enum: " + permittedType.getName()
            );
        }

        return Arrays.stream(constants)
                .map(AgentLogEventType.class::cast);
    }
}
