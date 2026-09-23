package space.br1440.platform.tracing.otel.javaagent.logging;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import space.br1440.platform.tracing.otel.javaagent.resilience.PlatformThrowables;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class BoundedThrowableRenderer {

    static final int MAX_CAUSES = 3;
    static final int MAX_SUPPRESSED = 3;
    static final int MAX_FRAMES = 30;
    static final int MAX_TOTAL_LENGTH = 4096;

    private static final String TRUNCATION_SUFFIX = "\n... (output truncated)";
    private static final String NATIVE_METHOD = "Native Method";
    private static final String UNKNOWN_SOURCE = "Unknown Source";

    static int maxDigestChars() {
        return MAX_TOTAL_LENGTH + TRUNCATION_SUFFIX.length();
    }

    public static String render(Throwable throwable) {
        if (throwable == null) {
            return null;
        }

        try {
            StringBuilder out = new StringBuilder(256);
            Set<Throwable> seen = newIdentitySet();
            appendChain(out, throwable, 0, seen);
            return capTotalLength(out);
        } catch (Throwable renderingFailure) {
            PlatformThrowables.propagateIfFatal(renderingFailure);
            return renderingFailureDetail(
                    throwable.getClass().getName(),
                    renderingFailure.getClass().getName()
            );
        }
    }

    static String renderFrame(StackTraceElement frame) {
        String location = frameLocation(frame.getFileName(), frame.getLineNumber());
        return frame.getClassName() + "." + frame.getMethodName() + "(" + location + ")";
    }

    static String renderingFailureDetail(String throwableClassName, String failureClassName) {
        String detail = throwableClassName + " (detail rendering failed: " + failureClassName + ")";
        if (detail.length() <= MAX_TOTAL_LENGTH) {
            return detail;
        }

        return detail.substring(0, MAX_TOTAL_LENGTH);
    }

    private static String frameLocation(String fileName, int lineNumber) {
        if (lineNumber == -2) {
            return NATIVE_METHOD;
        }

        if (fileName == null || lineNumber < 0) {
            return UNKNOWN_SOURCE;
        }

        return fileName + ":" + lineNumber;
    }

    private static Set<Throwable> newIdentitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    private static String capTotalLength(StringBuilder out) {
        if (out.length() <= MAX_TOTAL_LENGTH) {
            return out.toString();
        }

        return out.substring(0, MAX_TOTAL_LENGTH) + TRUNCATION_SUFFIX;
    }

    private static void appendChain(StringBuilder out, Throwable current, int depth, Set<Throwable> seen) {
        if (current == null || !seen.add(current)) {
            return;
        }

        if (depth > 0) {
            out.append("\ncaused by ");
        }

        appendOne(out, current);

        Throwable[] suppressed = current.getSuppressed();
        int suppressedLimit = Math.min(suppressed.length, MAX_SUPPRESSED);
        for (int i = 0; i < suppressedLimit; i++) {
            out.append("\nsuppressed ");
            appendOne(out, suppressed[i]);
        }

        Throwable cause = current.getCause();
        if (cause == null) {
            return;
        }

        if (depth == MAX_CAUSES) {
            out.append("\ncaused by ... (cause chain truncated)");
            return;
        }

        appendChain(out, cause, depth + 1, seen);
    }

    private static void appendOne(StringBuilder out, Throwable throwable) {
        out.append(throwable.getClass().getName());

        StackTraceElement[] frames = throwable.getStackTrace();
        int rendered = Math.min(frames.length, MAX_FRAMES);
        for (int i = 0; i < rendered; i++) {
            out.append("\n\tat ").append(renderFrame(frames[i]));
        }

        if (frames.length > rendered) {
            out.append("\n\t... ").append(frames.length - rendered).append(" more");
        }
    }
}
