package space.br1440.platform.logging.logbook.formatter.body_truncator;

import org.zalando.logbook.HttpMessage;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

abstract class AbstractTruncatedHttpMessage<T extends HttpMessage> implements HttpMessage {

    private static final byte[] EMPTY_BODY = new byte[0];
    private static final String BODY_TRUNCATED_SUFFIX = "...[TRUNCATED]";

    protected final T delegate;
    private final String resolvedBody;

    protected AbstractTruncatedHttpMessage(T delegate, int maxLength) throws IOException {
        this.delegate = delegate;
        String originalBody = delegate.getBodyAsString();

        boolean shouldTruncate = (maxLength > 0) && (originalBody.length() > maxLength);
        if (shouldTruncate) {
            this.resolvedBody = originalBody.substring(0, maxLength) + BODY_TRUNCATED_SUFFIX;
        } else {
            this.resolvedBody = originalBody;
        }
    }

    @Override
    public byte[] getBody() {
        if (resolvedBody == null) {
            return EMPTY_BODY;
        }

        Charset charset = getCharset();
        if (charset == null) {
            charset = StandardCharsets.UTF_8;
        }

        return resolvedBody.getBytes(charset);
    }

    @Override
    public String getBodyAsString() {
        return resolvedBody;
    }

}
