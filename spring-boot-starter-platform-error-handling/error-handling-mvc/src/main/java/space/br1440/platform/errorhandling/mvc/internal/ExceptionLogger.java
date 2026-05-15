package space.br1440.platform.errorhandling.mvc.internal;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import space.br1440.platform.errorhandling.core.util.log.LogStatementWriter;
import space.br1440.platform.errorhandling.core.util.sanitize.LogSanitizer;
import space.br1440.platform.errorhandling.extension.LogPolicy;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;
import space.br1440.platform.errorhandling.properties.LoggingStrategy;
import space.br1440.platform.errorhandling.model.ErrorEntry;

@Slf4j
public final class ExceptionLogger {

    private final ErrorHandlingProperties properties;

    public ExceptionLogger(@NonNull ErrorHandlingProperties properties) {
        this.properties = properties;
    }

    public void log(@NonNull Exception exception,
                    @NonNull LogPolicy logPolicy,
                    @NonNull HttpServletRequest request,
                    @NonNull ErrorEntry entry) {
        switch (properties.getLoggingStrategy()) {
            case CATEGORY -> logByPolicy(exception, logPolicy, request, entry);
            case ALL_DEBUG -> logAllDebug(exception, request);
            case ALL_ERROR -> logAllError(exception, request);
        }
    }

    private void logByPolicy(@NonNull Exception exception,
                             @NonNull LogPolicy logPolicy,
                             @NonNull HttpServletRequest request,
                             @NonNull ErrorEntry entry) {
        String method = LogSanitizer.sanitize(request.getMethod());
        String uri = LogSanitizer.sanitize(request.getRequestURI());
        String msg = LogSanitizer.sanitize(exception.getMessage());

        int code = entry.getHttpStatus().value();
        String type = exception.getClass().getName();
        boolean withStacktrace = properties.getStacktraceLogLevels().contains(logPolicy);

        LogStatementWriter.write(
                log, logPolicy, withStacktrace,
                method, uri, code, type, msg, exception
        );
    }

    private void logAllDebug(@NonNull Exception exception, @NonNull HttpServletRequest request) {
        log.debug("[{} {}] {}: {}",
                LogSanitizer.sanitize(request.getMethod()),
                LogSanitizer.sanitize(request.getRequestURI()),
                exception.getClass().getName(),
                LogSanitizer.sanitize(exception.getMessage())
        );
    }

    private void logAllError(@NonNull Exception exception, @NonNull HttpServletRequest request) {
        log.error("[{} {}] {}: {}",
                LogSanitizer.sanitize(request.getMethod()),
                LogSanitizer.sanitize(request.getRequestURI()),
                exception.getClass().getName(),
                LogSanitizer.sanitize(exception.getMessage()),
                exception
        );
    }
}
