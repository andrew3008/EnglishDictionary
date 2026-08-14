package space.br1440.platform.tracing.otel.javaagent.utils;

import lombok.experimental.UtilityClass;
import jakarta.annotation.Nullable;

@UtilityClass
public final class Strings {

    public static boolean isBlank(@Nullable String s) {
        return s == null || s.isBlank();
    }

    public static boolean isNotBlank(@Nullable String s) {
        return !isBlank(s);
    }
}
