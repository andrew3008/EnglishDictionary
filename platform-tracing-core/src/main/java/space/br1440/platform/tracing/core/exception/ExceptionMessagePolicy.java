package space.br1440.platform.tracing.core.exception;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import space.br1440.platform.tracing.core.utils.StringUtils;

import java.io.PrintWriter;
import java.io.StringWriter;

@RequiredArgsConstructor
public final class ExceptionMessagePolicy {

    private static final int MAX_MESSAGE_LENGTH = 512;

    private final boolean includeMessage;
    private final boolean includeStackTrace;

    @Nonnull
    public static ExceptionMessagePolicy secureDefault() {
        return new ExceptionMessagePolicy(false, false);
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
