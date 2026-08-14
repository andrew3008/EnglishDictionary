package space.br1440.platform.tracing.otel.javaagent.scrubbing;

import lombok.experimental.UtilityClass;

import space.br1440.platform.tracing.otel.javaagent.resilience.PlatformThrowables;

@UtilityClass
final class ThrowableDigest {

    private static final int MAX_CAUSE_DEPTH = 5;
    private static final int MAX_FRAMES = 12;
    private static final String INDENT = "\n\tat ";
    private static final String PLATFORM_PACKAGE_PREFIX = "space.br1440.platform.tracing.";
    private static final String PLATFORM_FRAME = "<platform-frame>";
    private static final String EXTERNAL_FRAME = "<external-frame>";

    static String render(Throwable t) {
        if (t == null) {
            return "n/a";
        }

        try {
            StringBuilder out = new StringBuilder(256);
            Throwable current = t;
            for (int depth = 0; current != null && depth <= MAX_CAUSE_DEPTH; depth++) {
                if (depth > 0) {
                    out.append("\ncaused by ");
                }
                appendThrowable(out, current);

                Throwable cause = current.getCause();
                current = (cause == current) ? null : cause;
                if (current != null && depth == MAX_CAUSE_DEPTH) {
                    out.append("\ncaused by ... (cause chain truncated)");
                }
            }

            return out.toString();
        } catch (Throwable renderingFailure) {
            PlatformThrowables.propagateIfFatal(renderingFailure);
            return t.getClass().getName() + " (detail rendering failed: " + renderingFailure.getClass().getName() + ")";
        }
    }

    private static void appendThrowable(StringBuilder out, Throwable t) {
        out.append(t.getClass().getName());

        StackTraceElement[] frames = t.getStackTrace();
        int rendered = Math.min(frames.length, MAX_FRAMES);
        for (int i = 0; i < rendered; i++) {
            out.append(INDENT).append(renderFrame(frames[i]));
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
