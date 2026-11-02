package space.br1440.platform.tracing.otel.exception;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import space.br1440.platform.common.util.StringUtils;

public final class ExceptionRecordingPolicy {

    private static final int MAX_MESSAGE_LENGTH = 512;

    private final boolean includeMessage;

    private final boolean includeStackTrace;

    private final BoundedExceptionStackRenderer stackRenderer;

    public ExceptionRecordingPolicy(boolean includeMessage, boolean includeStackTrace) {
        this(includeMessage, includeStackTrace, BoundedExceptionStackRenderer.DEFAULT_CHARACTER_BUDGET);
    }

    ExceptionRecordingPolicy(boolean includeMessage, boolean includeStackTrace, int characterBudget) {
        this.includeMessage = includeMessage;
        this.includeStackTrace = includeStackTrace;
        this.stackRenderer = new BoundedExceptionStackRenderer(
                characterBudget,
                ExceptionRecordingPolicy::sanitizedMessageOrNull
        );
    }

    @Nonnull
    public static ExceptionRecordingPolicy secureDefault() {
        return new ExceptionRecordingPolicy(false, false);
    }

    @Nullable
    public String sanitizeOrNull(@Nullable Throwable exception) {
        if (!includeMessage || (exception == null)) {
            return null;
        }

        return sanitizedMessageOrNull(exception);
    }

    @Nullable
    RenderedExceptionStack renderStack(@Nullable Throwable exception) {
        if (!includeStackTrace || (exception == null)) {
            return null;
        }

        return stackRenderer.render(exception);
    }

    @Nullable
    static String sanitizedMessageOrNull(@Nullable Throwable exception) {
        if (exception == null) {
            return null;
        }

        String message = exception.getMessage();
        if (StringUtils.isNullOrBlank(message)) {
            return null;
        }

        String trimmed = message.strip();
        if (trimmed.length() > MAX_MESSAGE_LENGTH) {
            return trimmed.substring(0, MAX_MESSAGE_LENGTH);
        }

        return trimmed;
    }
}
