package space.br1440.platform.errorhandling.core.util.log;

import lombok.experimental.UtilityClass;
import org.slf4j.Logger;
import org.springframework.lang.NonNull;
import space.br1440.platform.errorhandling.extension.LogPolicy;

@UtilityClass
public final class LogStatementWriter {

    public static final String LOG_FORMAT = "[{} {}] {} {}: {}";

    public static void write(@NonNull Logger log,
                             @NonNull LogPolicy policy,
                             boolean withStacktrace,
                             @NonNull String method,
                             @NonNull String uri,
                             int httpStatus,
                             @NonNull String exceptionType,
                             String message,
                             @NonNull Throwable exception) {
        switch (policy) {
            case DEBUG -> writeDebug(log, withStacktrace, method, uri, httpStatus, exceptionType, message, exception);
            case INFO -> writeInfo(log, withStacktrace, method, uri, httpStatus, exceptionType, message, exception);
            case WARN -> writeWarn(log, withStacktrace, method, uri, httpStatus, exceptionType, message, exception);
            case ERROR -> log.error(LOG_FORMAT, method, uri, httpStatus, exceptionType, message, exception);
        }
    }

    private static void writeDebug(@NonNull Logger log, boolean withStacktrace,
                                   @NonNull String method, @NonNull String uri,
                                   int httpStatus, @NonNull String exceptionType,
                                   String message, @NonNull Throwable exception) {
        if (withStacktrace) {
            log.debug(LOG_FORMAT, method, uri, httpStatus, exceptionType, message, exception);
        } else {
            log.debug(LOG_FORMAT, method, uri, httpStatus, exceptionType, message);
        }
    }

    private static void writeInfo(@NonNull Logger log, boolean withStacktrace,
                                  @NonNull String method, @NonNull String uri,
                                  int httpStatus, @NonNull String exceptionType,
                                  String message, @NonNull Throwable exception) {
        if (withStacktrace) {
            log.info(LOG_FORMAT, method, uri, httpStatus, exceptionType, message, exception);
        } else {
            log.info(LOG_FORMAT, method, uri, httpStatus, exceptionType, message);
        }
    }

    private static void writeWarn(@NonNull Logger log, boolean withStacktrace,
                                  @NonNull String method, @NonNull String uri,
                                  int httpStatus, @NonNull String exceptionType,
                                  String message, @NonNull Throwable exception) {
        if (withStacktrace) {
            log.warn(LOG_FORMAT, method, uri, httpStatus, exceptionType, message, exception);
        } else {
            log.warn(LOG_FORMAT, method, uri, httpStatus, exceptionType, message);
        }
    }
}
