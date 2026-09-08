package space.br1440.platform.tracing.autoconfigure.reactive;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.util.MultiValueMap;

import space.br1440.platform.tracing.api.propagation.PlatformHeaders;

final class PlatformOwnedServerHttpResponse extends ServerHttpResponseDecorator {

    private final PlatformOwnedHttpHeaders headers;
    private String requestIdName;
    private String requestIdValue;
    private String traceIdValue;

    PlatformOwnedServerHttpResponse(ServerHttpResponse delegate) {
        super(delegate);
        this.headers = new PlatformOwnedHttpHeaders(delegate.getHeaders(), this);
    }

    void protectRequestId(String name, String value) {
        this.requestIdName = name;
        this.requestIdValue = value;
    }

    void protectTraceId(String value) {
        this.traceIdValue = value;
    }

    void installCanonical() {
        HttpHeaders delegateHeaders = getDelegate().getHeaders();
        if (requestIdValue != null && requestIdName != null) {
            delegateHeaders.set(requestIdName, requestIdValue);
        }

        if (traceIdValue != null) {
            delegateHeaders.set(PlatformHeaders.X_TRACE_ID, traceIdValue);
        }
    }

    @Override
    @NonNull
    public HttpHeaders getHeaders() {
        return headers;
    }

    boolean isProtected(String name) {
        if (name == null) {
            return false;
        }

        if (traceIdValue != null && PlatformHeaders.X_TRACE_ID.equalsIgnoreCase(name)) {
            return true;
        }

        return requestIdValue != null
                && requestIdName != null
                && requestIdName.equalsIgnoreCase(name);
    }

    private static final class PlatformOwnedHttpHeaders extends HttpHeaders {

        private final PlatformOwnedServerHttpResponse owner;

        private PlatformOwnedHttpHeaders(HttpHeaders delegate, PlatformOwnedServerHttpResponse owner) {
            super(delegate);
            this.owner = owner;
        }

        @Override
        public void set(@NonNull String headerName, @Nullable String headerValue) {
            if (owner.isProtected(headerName)) {
                return;
            }

            super.set(headerName, headerValue);
        }

        @Override
        public void add(@NonNull String headerName, @Nullable String headerValue) {
            if (owner.isProtected(headerName)) {
                return;
            }
            super.add(headerName, headerValue);
        }

        @Override
        public void addAll(@NonNull String key, @NonNull List<? extends String> values) {
            if (owner.isProtected(key)) {
                return;
            }

            super.addAll(key, values);
        }

        @Override
        public void addAll(@NonNull MultiValueMap<String, String> values) {
            values.forEach(this::addAll);
        }

        @Override
        public void setAll(@NonNull Map<String, String> values) {
            values.forEach(this::set);
        }

        @Override
        @NonNull
        public List<String> put(@NonNull String key, @NonNull List<String> value) {
            if (owner.isProtected(key)) {
                return getOrEmpty(key);
            }

            return super.put(key, value);
        }

        @Override
        public void putAll(@NonNull Map<? extends String, ? extends List<String>> map) {
            map.forEach(this::put);
        }

        @Override
        @NonNull
        public List<String> remove(@NonNull Object key) {
            if (key instanceof String name && owner.isProtected(name)) {
                return getOrEmpty(name);
            }

            return super.remove(key);
        }
    }
}
