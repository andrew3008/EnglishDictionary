package space.br1440.platform.errorhandling.core.util;

import lombok.experimental.UtilityClass;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Collectors;

@UtilityClass
public final class StringUtils {

    private static final String TRUNCATION_MARKER = " [TRUNCATED]";

    @Nullable
    public static String truncate(@Nullable String value, int maxLength) {
        if (maxLength <= 0) {
            throw new IllegalArgumentException("maxLength must be positive, got: " + maxLength);
        }

        if (value == null || value.length() <= maxLength) {
            return value;
        }

        return value.substring(0, maxLength) + TRUNCATION_MARKER;
    }

    @NonNull
    public static String truncateNonNull(@NonNull String value, int maxLength) {
        if (maxLength <= 0) {
            throw new IllegalArgumentException("maxLength must be positive, got: " + maxLength);
        }

        if (value.length() <= maxLength) {
            return value;
        }

        return value.substring(0, maxLength) + TRUNCATION_MARKER;
    }

    @NonNull
    public static String joinOrDefault(@Nullable String[] values,
                                       @NonNull String delimiter,
                                       @NonNull String defaultValue) {
        if (values == null || values.length == 0) {
            return defaultValue;
        }

        String joined = Arrays.stream(values)
                .filter(Objects::nonNull)
                .collect(Collectors.joining(delimiter));
        return joined.isEmpty() ? defaultValue : joined;
    }

    @NonNull
    public static String firstNonBlank(@Nullable String... candidates) {
        if (candidates == null) {
            return "Unknown error";
        }

        for (String s : candidates) {
            if (s != null && !s.isBlank()) {
                return s;
            }
        }

        return "Unknown error";
    }

    public static boolean hasText(@Nullable final String str) {
        return str != null && !str.isBlank();
    }

    public static String orElse(@Nullable final String str, final String fallback) {
        return hasText(str) ? str : fallback;
    }
}
