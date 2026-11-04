package space.br1440.platform.tracing.e2e.startup;

import java.io.PrintStream;

public final class PostReadyLinkageFailure extends LinkageError {

    public static final String THROWN_PROPERTY = "platform.tracing.e2e.post-ready-failure.thrown";
    public static final String PRINTED_PROPERTY = "platform.tracing.e2e.post-ready-failure.printed";

    private static final String AGENT_ENTRY_CLASS = "io.opentelemetry.javaagent.OpenTelemetryAgent";
    private static final String AGENT_ENTRY_METHOD = "startAgent";

    PostReadyLinkageFailure() {
        super("Test listener failed fatally after platform READY publication");
    }

    @Override
    public void printStackTrace(PrintStream stream) {
        if (printedByAgentStartBoundary()) {
            System.getProperties().put(PRINTED_PROPERTY, this);
        }

        super.printStackTrace(stream);
    }

    // Фиксируется только печать catch-блоком upstream premain: печать платформенным логгером
    // внутри того же premain не доказывает выход экземпляра из AgentStarter.start().
    private static boolean printedByAgentStartBoundary() {
        return StackWalker.getInstance().walk(frames -> frames
                .filter(frame -> !frame.getClassName().equals(PostReadyLinkageFailure.class.getName())
                        && !frame.getClassName().equals(Throwable.class.getName()))
                .findFirst()
                .map(frame -> AGENT_ENTRY_CLASS.equals(frame.getClassName())
                        && AGENT_ENTRY_METHOD.equals(frame.getMethodName()))
                .orElse(false));
    }
}
