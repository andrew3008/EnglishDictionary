package space.br1440.platform.tracing.otel.javaagent.logging;

import java.util.IdentityHashMap;
import java.util.Set;

import space.br1440.platform.tracing.otel.javaagent.resilience.PlatformThrowables;

public final class BoundedThrowableRenderer {

    static final int MAX_CAUSES = AgentLogEvent.MAX_CAUSES;
    static final int MAX_SUPPRESSED = AgentLogEvent.MAX_SUPPRESSED;
    static final int MAX_FRAMES = AgentLogEvent.MAX_FRAMES;
    static final int MAX_TOTAL_LENGTH = 4096;

    private static final String PLATFORM_PACKAGE_PREFIX = "space.br1440.platform.tracing.";
    private static final String PLATFORM_FRAME = "<platform-frame>";
    private static final String EXTERNAL_FRAME = "<external-frame>";
    private static final String TRUNCATION_SUFFIX = "\n... (output truncated)";

    static int maxDigestChars() {
        return MAX_TOTAL_LENGTH + TRUNCATION_SUFFIX.length();
    }

    private BoundedThrowableRenderer() {
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
            return throwable.getClass().getName()
                    + " (detail rendering failed: "
                    + renderingFailure.getClass().getName()
                    + ")";
        }
    }

    private static Set<Throwable> newIdentitySet() {
        return java.util.Collections.newSetFromMap(new IdentityHashMap<>());
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

    private static String renderFrame(StackTraceElement frame) {
        String className = frame.getClassName();
        if (className.startsWith(PLATFORM_PACKAGE_PREFIX)) {
            return PLATFORM_FRAME;
        }

        return EXTERNAL_FRAME;
    }
}
