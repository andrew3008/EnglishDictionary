package space.br1440.platform.logging.logbook.formatter.body_truncator;

import org.zalando.logbook.ForwardingHttpRequest;
import org.zalando.logbook.HttpRequest;

import java.io.IOException;

public class TruncatedHttpRequest extends AbstractTruncatedHttpMessage<HttpRequest> implements ForwardingHttpRequest {

    public TruncatedHttpRequest(HttpRequest delegate, int maxLength) throws IOException {
        super(delegate, maxLength);
    }

    @Override
    public HttpRequest delegate() {
        return delegate;
    }

}
