package space.br1440.platform.tracing.otel.exception;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import space.br1440.platform.tracing.runtime.scrubbing.UrlCredentialMasker;

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
                this::sanitizeOrNull
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
    String renderStack(@Nullable Throwable exception, @Nullable String rootMessage) {
        if (!includeStackTrace || (exception == null)) {
            return null;
        }

        return stackRenderer.render(exception, includeMessage ? rootMessage : null);
    }

    @Nullable
    static String sanitizedMessageOrNull(@Nullable Throwable exception) {
        if (exception == null) {
            return null;
        }

        String message = exception.getMessage();
        if (message == null) {
            return null;
        }

        String masked = UrlCredentialMasker.mask(message);
        if (masked == null) {
            return null;
        }

        String trimmed = masked.strip();
        if (trimmed.isEmpty()) {
            return null;
        }

        if (trimmed.length() > MAX_MESSAGE_LENGTH) {
            int end = MAX_MESSAGE_LENGTH;
            if (Character.isHighSurrogate(trimmed.charAt(end - 1)) && Character.isLowSurrogate(trimmed.charAt(end))) {
                end--;
            }

            return trimmed.substring(0, end);
        }

        return trimmed;
    }
}
