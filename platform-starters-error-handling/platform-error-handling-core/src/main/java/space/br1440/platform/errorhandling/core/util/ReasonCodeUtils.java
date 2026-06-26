package space.br1440.platform.errorhandling.core.util;

import lombok.experimental.UtilityClass;
import org.springframework.lang.NonNull;

/**
 * Преобразование класса исключения в стабильный machine-readable {@code reason}-код
 * (UPPER_SNAKE по простому имени класса без суффикса {@code Exception}).
 */
@UtilityClass
public final class ReasonCodeUtils {

    @NonNull
    public static String toReasonCode(@NonNull Class<?> exceptionClass) {
        String simple = exceptionClass.getSimpleName();
        if (simple.endsWith("Exception")) {
            simple = simple.substring(0, simple.length() - "Exception".length());
        }

        return camelCaseToUpperSnake(simple);
    }

    @NonNull
    private static String camelCaseToUpperSnake(@NonNull String camel) {
        if (camel.isEmpty()) {
            return camel;
        }

        StringBuilder out = new StringBuilder(camel.length() + 8);
        out.append(Character.toUpperCase(camel.charAt(0)));
        for (int i = 1; i < camel.length(); i++) {
            char c = camel.charAt(i);
            char prev = camel.charAt(i - 1);
            if (Character.isUpperCase(c) && Character.isLowerCase(prev)) {
                out.append('_');
            }
            out.append(Character.toUpperCase(c));
        }
        return out.toString();
    }
}
