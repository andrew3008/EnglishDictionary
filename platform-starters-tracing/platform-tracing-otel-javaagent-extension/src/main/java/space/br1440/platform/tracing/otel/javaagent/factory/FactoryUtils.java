package space.br1440.platform.tracing.otel.javaagent.factory;

import java.util.List;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class FactoryUtils {

    public static <T> T orDefault(T value, T fallback) {
        return switch (value) {
            case null -> fallback;
            case String s when s.isEmpty() -> fallback;
            case List<?> list when list.isEmpty() -> fallback;
            default -> value;
        };
    }
}
