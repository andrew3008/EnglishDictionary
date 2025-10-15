package space.br1440.platform.logging.logbook.formatter;

import org.zalando.logbook.*;
import space.br1440.platform.logging.configuration.LoggingProperties;

import javax.annotation.ParametersAreNonnullByDefault;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class ExcludedHeadersHttpLogFormatter implements HttpLogFormatter {

    private final LoggingProperties props;
    private final HttpLogFormatter delegate;

    public ExcludedHeadersHttpLogFormatter(LoggingProperties props, HttpLogFormatter delegate) {
        this.props = props;
        this.delegate = delegate;
    }

    @Override
    @ParametersAreNonnullByDefault
    public String format(final Precorrelation precorrelation, final HttpRequest request) throws IOException {
        LoggingProperties.HttpLoggingSettings.HeaderProperties cfg = props.getRequest().getHeaders();
        final HttpRequest view = cfg.getEnable()
                ? wrapRequestWithHeaders(request, filter(request.getHeaders(), cfg.getExcludeHeaders()))
                : wrapRequestWithHeaders(request, HttpHeaders.empty());
        return delegate.format(precorrelation, view);
    }

    @Override
    @ParametersAreNonnullByDefault
    public String format(final Correlation correlation, final HttpResponse response) throws IOException {
        LoggingProperties.HttpLoggingSettings.HeaderProperties cfg = props.getResponse().getHeaders();
        final HttpResponse view = cfg.getEnable()
                ? wrapResponseWithHeaders(response, filter(response.getHeaders(), cfg.getExcludeHeaders()))
                : wrapResponseWithHeaders(response, HttpHeaders.empty());
        return delegate.format(correlation, view);
    }

    private HttpHeaders filter(final HttpHeaders sourceHeaders, final List<String> excludeHeaderNames) {
        if (excludeHeaderNames == null || excludeHeaderNames.isEmpty()) {
            return sourceHeaders;
        }

        Map<String, List<String>> filteredMap = sourceHeaders.entrySet().stream()
                .filter(entry -> excludeHeaderNames.stream()
                        .noneMatch(prefix ->
                                entry.getKey().regionMatches(true, 0, prefix, 0, prefix.length())
                        )
                )
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue
                ));

        return HttpHeaders.of(filteredMap);
    }

    private HttpRequest wrapRequestWithHeaders(final HttpRequest original, final HttpHeaders headers) {
        return new ForwardingHttpRequest() {
            @Override public HttpRequest delegate() { return original; }
            @Override public HttpHeaders getHeaders() { return headers; }
        };
    }

    private HttpResponse wrapResponseWithHeaders(final HttpResponse original, final HttpHeaders headers) {
        return new ForwardingHttpResponse() {
            @Override public HttpResponse delegate() { return original; }
            @Override public HttpHeaders getHeaders() { return headers; }
        };
    }

}
