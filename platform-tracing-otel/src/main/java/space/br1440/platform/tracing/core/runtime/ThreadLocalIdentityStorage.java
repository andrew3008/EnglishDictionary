package space.br1440.platform.tracing.core.runtime;

import java.util.Objects;
import java.util.Optional;

import org.slf4j.MDC;

import space.br1440.platform.tracing.api.CorrelationScope;
import space.br1440.platform.tracing.api.mdc.TracingMdcKeys;

/** Instance-local carrier для disabled runtime; WebFlux использует Reactor Context поверх него. */
final class ThreadLocalIdentityStorage {

    private final ThreadLocal<IdentityFrame> current = new ThreadLocal<>();

    Optional<String> requestId() {
        IdentityFrame frame = current.get();
        return Optional.ofNullable(frame == null ? null : frame.requestId());
    }

    Optional<String> correlationId() {
        IdentityFrame frame = current.get();
        return Optional.ofNullable(frame == null ? null : frame.correlationId());
    }

    String requireCanonicalCorrelationId(String correlationId) {
        return requireCanonical(correlationId);
    }

    CorrelationScope openCorrelationScope(String correlationId) {
        String canonical = requireCanonical(correlationId);
        IdentityFrame previous = current.get();
        return open(new IdentityFrame(
                previous == null ? null : previous.requestId(),
                canonical,
                previous));
    }

    private static String requireCanonical(String correlationId) {
        Objects.requireNonNull(correlationId, "correlationId");
        if (correlationId.isEmpty() || correlationId.length() > 128) {
            throw new IllegalArgumentException("correlationId length must be between 1 and 128");
        }
        for (int index = 0; index < correlationId.length(); index++) {
            char character = correlationId.charAt(index);
            boolean canonical = character >= 'A' && character <= 'Z'
                    || character >= 'a' && character <= 'z'
                    || character >= '0' && character <= '9'
                    || character == '_'
                    || character == '-';
            if (!canonical) {
                throw new IllegalArgumentException("correlationId must match [A-Za-z0-9_-]{1,128}");
            }
        }
        return correlationId;
    }

    CorrelationScope openRequestScope(String requestId) {
        Objects.requireNonNull(requestId, "requestId");
        IdentityFrame previous = current.get();
        return open(new IdentityFrame(
                requestId,
                previous == null ? null : previous.correlationId(),
                previous));
    }

    private CorrelationScope open(IdentityFrame frame) {
        String previousRequestId = MDC.get(TracingMdcKeys.REQUEST_ID);
        String previousCorrelationId = MDC.get(TracingMdcKeys.CORRELATION_ID);
        current.set(frame);
        putOrRemove(TracingMdcKeys.REQUEST_ID, frame.requestId());
        putOrRemove(TracingMdcKeys.CORRELATION_ID, frame.correlationId());
        return new IdentityScope(
                Thread.currentThread(),
                frame,
                previousRequestId,
                previousCorrelationId);
    }

    private static void putOrRemove(String key, String value) {
        if (value == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
    }

    private record IdentityFrame(String requestId, String correlationId, IdentityFrame previous) {
    }

    private final class IdentityScope implements CorrelationScope {

        private final Thread owner;
        private final IdentityFrame installed;
        private final String previousRequestId;
        private final String previousCorrelationId;
        private boolean closed;

        private IdentityScope(Thread owner,
                              IdentityFrame installed,
                              String previousRequestId,
                              String previousCorrelationId) {
            this.owner = owner;
            this.installed = installed;
            this.previousRequestId = previousRequestId;
            this.previousCorrelationId = previousCorrelationId;
        }

        @Override
        public void close() {
            if (Thread.currentThread() != owner) {
                throw new IllegalStateException("CorrelationScope must be closed by its owner thread");
            }
            if (closed) {
                return;
            }
            if (current.get() != installed) {
                throw new IllegalStateException("CorrelationScope must be closed in LIFO order");
            }
            if (installed.previous() == null) {
                current.remove();
            } else {
                current.set(installed.previous());
            }
            closed = true;
            putOrRemove(TracingMdcKeys.REQUEST_ID, previousRequestId);
            putOrRemove(TracingMdcKeys.CORRELATION_ID, previousCorrelationId);
        }
    }
}
