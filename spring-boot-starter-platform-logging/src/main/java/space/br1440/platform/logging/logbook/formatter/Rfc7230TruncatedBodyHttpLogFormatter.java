package space.br1440.platform.logging.logbook.formatter;

import org.zalando.logbook.*;
import org.zalando.logbook.core.DefaultHttpLogFormatter;
import space.br1440.platform.logging.configuration.LoggingProperties;
import space.br1440.platform.logging.logbook.formatter.body_truncator.TruncatedHttpRequest;
import space.br1440.platform.logging.logbook.formatter.body_truncator.TruncatedHttpResponse;

import javax.annotation.ParametersAreNonnullByDefault;
import java.io.IOException;

public class Rfc7230TruncatedBodyHttpLogFormatter implements HttpLogFormatter {

    private final LoggingProperties properties;
    private final HttpLogFormatter delegate;

    public Rfc7230TruncatedBodyHttpLogFormatter(LoggingProperties properties) {
        this.properties = properties;
        this.delegate = new DefaultHttpLogFormatter();
    }

    @Override
    @ParametersAreNonnullByDefault
    public String format(Precorrelation precorrelation, HttpRequest request) throws IOException {
        if (!properties.getRequest().getBody().getEnable()) {
            return delegate.format(precorrelation, request.withoutBody());
        }

        int maxLength = properties.getRequest().getBody().getMaxLength();
        if (maxLength > 0) {
            HttpRequest truncatedRequest = new TruncatedHttpRequest(request, maxLength);
            return delegate.format(precorrelation, truncatedRequest);
        }

        return delegate.format(precorrelation, request);
    }

    @Override
    @ParametersAreNonnullByDefault
    public String format(Correlation correlation, HttpResponse response) throws IOException {
        if (!properties.getResponse().getBody().getEnable()) {
            return delegate.format(correlation, response.withoutBody());
        }

        int maxLength = properties.getResponse().getBody().getMaxLength();
        if (maxLength > 0) {
            response = new TruncatedHttpResponse(response, maxLength);
        }

        return delegate.format(correlation, response);
    }

}
