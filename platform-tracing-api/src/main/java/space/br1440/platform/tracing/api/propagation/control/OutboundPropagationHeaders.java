package space.br1440.platform.tracing.api.propagation.control;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

import jakarta.annotation.Nonnull;

/**
 * Immutable результат формирования трёх платформенных заголовков для исходящего carrier.
 */
public record OutboundPropagationHeaders(
        @Nonnull Optional<Header> forceTrace,
        @Nonnull Optional<Header> qaTrace,
        @Nonnull Optional<Header> requestId) {

    public static final OutboundPropagationHeaders EMPTY =
            new OutboundPropagationHeaders(Optional.empty(), Optional.empty(), Optional.empty());

    public OutboundPropagationHeaders {
        Objects.requireNonNull(forceTrace, "forceTrace");
        Objects.requireNonNull(qaTrace, "qaTrace");
        Objects.requireNonNull(requestId, "requestId");
    }

    /**
     * Проверенный заголовок, безопасный для записи transport adapter-ом.
     */
    public record Header(@Nonnull String name, @Nonnull String value) {

        private static final Pattern HEADER_NAME =
                Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");

        public Header {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(value, "value");
            if (!HEADER_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("invalid header name");
            }
            if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("header value must not contain CR/LF");
            }
        }
    }
}
