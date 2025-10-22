package space.br1440.platform.logging.logbook.writer;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.zalando.logbook.Correlation;
import org.zalando.logbook.HttpLogWriter;
import org.zalando.logbook.Precorrelation;
import space.br1440.platform.logging.mdc.MDCLogKey;

import javax.annotation.ParametersAreNonnullByDefault;

@Slf4j
public class PlatformHttpLogWriter implements HttpLogWriter {

    @Override
    @ParametersAreNonnullByDefault
    public void write(Precorrelation precorrelation, String request) {
        log.info(request);
    }

    @Override
    @ParametersAreNonnullByDefault
    public void write(Correlation correlation, String response) {
        int status = parseStatusFromMDC();
        Severity severity = resolveSeverity(status);

        switch (severity) {
            case ERROR -> log.error(response);
            case WARN -> log.warn(response);
            case INFO -> log.info(response);
        }
    }

    private int parseStatusFromMDC() {
        String statusStr = MDC.get(MDCLogKey.HTTP_STATUS_CODE.value());
        if (statusStr == null) {
            return HttpStatus.INTERNAL_SERVER_ERROR.value();
        }

        try {
            return Integer.parseInt(statusStr);
        } catch (NumberFormatException e) {
            return HttpStatus.INTERNAL_SERVER_ERROR.value();
        }
    }

    private Severity resolveSeverity(int statusCode) {
        HttpStatus.Series series = HttpStatus.Series.valueOf(statusCode);

        return switch (series) {
            case CLIENT_ERROR -> switch (HttpStatus.valueOf(statusCode)) {
                case UNAUTHORIZED, FORBIDDEN -> Severity.WARN; // потенциальные проблемы безопасности
                case NOT_FOUND -> Severity.INFO; // ресурс не найден, но это не всегда ошибка
                default -> Severity.WARN; // 4xx — ошибки, вызванные некорректным запросом клиента
            };

            case SERVER_ERROR -> Severity.ERROR; // 5xx — ошибки сервера

            default -> Severity.INFO; //  1xx (инфо), 2xx (успех), 3xx (редиректы)
        };
    }

    private enum Severity {
        ERROR,
        WARN,
        INFO
    }

}
