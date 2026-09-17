package space.br1440.platform.tracing.otel.exception;

import java.io.PrintWriter;
import java.io.StringWriter;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import space.br1440.platform.common.util.StringUtils;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public final class ExceptionRecordingPolicy {

    private static final int MAX_MESSAGE_LENGTH = 512;

    private final boolean includeMessage;
    private final boolean includeStackTrace;

    @Nonnull
    public static ExceptionRecordingPolicy secureDefault() {
        return new ExceptionRecordingPolicy(false, false);
    }

    @Nullable
    public String sanitizeOrNull(@Nullable Throwable exception) {
        if (!includeMessage || exception == null) {
            return null;
        }

        String message = exception.getMessage();
        if (StringUtils.isNullOrBlank(message)) {
            return null;
        }

        String trimmed = message.strip();
        return (trimmed.length() > MAX_MESSAGE_LENGTH) ? trimmed.substring(0, MAX_MESSAGE_LENGTH) : trimmed;
    }

    @Nullable
    public String sanitizedStackOrNull(@Nullable Throwable exception) {
        if (!includeStackTrace || exception == null) {
            return null;
        }

        StringWriter sw = new StringWriter();
        exception.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
