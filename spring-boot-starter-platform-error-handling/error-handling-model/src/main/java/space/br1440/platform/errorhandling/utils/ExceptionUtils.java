package space.br1440.platform.errorhandling.utils;

import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

@UtilityClass
public final class ExceptionUtils {

    public static String getStackTrace(@NotNull Throwable exception) {
        return getStackTrace(exception, Integer.MAX_VALUE);
    }

    public static String getStackTrace(@NotNull Throwable exception, int maxLines) {
        StringWriter sw = new StringWriter();
        exception.printStackTrace(new PrintWriter(sw));

        String full = sw.toString();
        String[] lines = full.split("\\r?\\n");
        if (lines.length <= maxLines) {
            return full;
        }

        return String.join("\n", Arrays.copyOf(lines, maxLines))
                + "\n\t... [truncated, " + (lines.length - maxLines) + " more lines]";
    }

    public static Throwable getRootCause(@NotNull Throwable exception) {
        return getRootCause(exception, Integer.MAX_VALUE);
    }

    public static Throwable getRootCause(@NotNull Throwable exception, int maxCauseDepth) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());

        Throwable root = exception;
        int depth = 0;
        seen.add(root);
        while (root.getCause() != null && seen.add(root.getCause()) && depth < maxCauseDepth) {
            root = root.getCause();
            depth++;
        }

        return root;
    }

    public static String getRootCauseMessage(@NotNull Throwable exception, int maxCauseDepth, @NotNull String fallback) {
        Throwable root = getRootCause(exception, maxCauseDepth);
        String message = root.getMessage();
        return (message != null && !message.isBlank()) ? message : fallback;
    }

    public static String getMessageOrTypeName(@NotNull Throwable exception) {
        String message = exception.getMessage();
        return (message != null && !message.isBlank()) ? message : exception.getClass().getName();
    }
}
