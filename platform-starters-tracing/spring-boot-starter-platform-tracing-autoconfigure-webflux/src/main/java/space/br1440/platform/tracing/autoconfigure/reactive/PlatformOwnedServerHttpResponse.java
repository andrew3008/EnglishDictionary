package space.br1440.platform.tracing.autoconfigure.reactive;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
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
        public void set(String headerName, String headerValue) {
            if (owner.isProtected(headerName)) {
                return;
            }

            super.set(headerName, headerValue);
        }

        @Override
        public void add(String headerName, String headerValue) {
            if (owner.isProtected(headerName)) {
                return;
            }
            super.add(headerName, headerValue);
        }

        @Override
        public void addAll(String key, List<? extends String> values) {
            if (owner.isProtected(key)) {
                return;
            }

            super.addAll(key, values);
        }

        @Override
        public void addAll(MultiValueMap<String, String> values) {
            values.forEach((key, headerValues) -> addAll(key, headerValues));
        }

        @Override
        public void setAll(Map<String, String> values) {
            values.forEach(this::set);
        }

        @Override
        public List<String> put(String key, List<String> value) {
            if (owner.isProtected(key)) {
                return get(key);
            }

            return super.put(key, value);
        }

        @Override
        public void putAll(Map<? extends String, ? extends List<String>> map) {
            map.forEach(this::put);
        }

        @Override
        public List<String> remove(Object key) {
            if (key instanceof String name && owner.isProtected(name)) {
                return get(name);
            }

            return super.remove(key);
        }
    }
}
