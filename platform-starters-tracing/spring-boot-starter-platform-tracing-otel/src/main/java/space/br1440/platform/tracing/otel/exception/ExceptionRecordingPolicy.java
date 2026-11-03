package space.br1440.platform.tracing.otel.exception;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import space.br1440.platform.common.util.StringUtils;
import space.br1440.platform.tracing.runtime.scrubbing.DiagnosticTextSanitizer;

public final class ExceptionRecordingPolicy {

    private static final int MAX_MESSAGE_LENGTH = 512;
    private static final int MAX_STACK_TRACE_LENGTH = 4_096;

    private final boolean includeMessage;

    @Nullable
    private final BoundedExceptionStackRenderer stackRenderer;

    public ExceptionRecordingPolicy(boolean includeMessage, boolean includeStackTrace, int sdkAttributeValueLengthLimit) {
        if (includeStackTrace && sdkAttributeValueLengthLimit <= 0) {
            throw new IllegalArgumentException("SDK attribute value length limit must be positive");
        }

        this.includeMessage = includeMessage;
        this.stackRenderer = includeStackTrace && sdkAttributeValueLengthLimit > 1
                ? new BoundedExceptionStackRenderer(
                        Math.min(sdkAttributeValueLengthLimit - 1, MAX_STACK_TRACE_LENGTH), this::sanitizeOrNull)
                : null;
    }

    @Nonnull
    public static ExceptionRecordingPolicy secureDefault() {
        return new ExceptionRecordingPolicy(true, false, 0);
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
        if (stackRenderer == null || exception == null) {
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

        String masked = DiagnosticTextSanitizer.mask(message);
        if (StringUtils.isNullOrBlank(masked)) {
            return null;
        }

        String trimmed = masked.strip();
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
