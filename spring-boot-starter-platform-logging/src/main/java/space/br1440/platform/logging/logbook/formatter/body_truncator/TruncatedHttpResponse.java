package space.br1440.platform.logging.logbook.formatter.body_truncator;

import org.zalando.logbook.ForwardingHttpResponse;
import org.zalando.logbook.HttpResponse;

import java.io.IOException;

public class TruncatedHttpResponse extends AbstractTruncatedHttpMessage<HttpResponse> implements ForwardingHttpResponse {

    public TruncatedHttpResponse(HttpResponse delegate, int maxLength) throws IOException {
        super(delegate, maxLength);
    }

    @Override
    public HttpResponse delegate() {
        return delegate;
    }

}
