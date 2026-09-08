package space.br1440.platform.tracing.autoconfigure.servlet;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

import space.br1440.platform.tracing.api.propagation.PlatformHeaders;

final class PlatformOwnedHttpServletResponse extends HttpServletResponseWrapper {

    private String requestIdName;
    private String requestIdValue;
    private String traceIdValue;

    PlatformOwnedHttpServletResponse(HttpServletResponse response) {
        super(response);
    }

    void protectRequestId(String name, String value) {
        this.requestIdName = name;
        this.requestIdValue = value;
    }

    void protectTraceId(String value) {
        this.traceIdValue = value;
    }

    void installCanonical() {
        HttpServletResponse delegate = (HttpServletResponse) getResponse();
        if (requestIdValue != null && requestIdName != null) {
            delegate.setHeader(requestIdName, requestIdValue);
        }

        if (traceIdValue != null) {
            delegate.setHeader(PlatformHeaders.X_TRACE_ID, traceIdValue);
        }
    }

    @Override
    public void setHeader(String name, String value) {
        if (isProtected(name)) {
            return;
        }

        super.setHeader(name, value);
    }

    @Override
    public void addHeader(String name, String value) {
        if (isProtected(name)) {
            return;
        }

        super.addHeader(name, value);
    }

    @Override
    public void setIntHeader(String name, int value) {
        if (isProtected(name)) {
            return;
        }

        super.setIntHeader(name, value);
    }

    @Override
    public void addIntHeader(String name, int value) {
        if (isProtected(name)) {
            return;
        }

        super.addIntHeader(name, value);
    }

    @Override
    public void setDateHeader(String name, long date) {
        if (isProtected(name)) {
            return;
        }

        super.setDateHeader(name, date);
    }

    @Override
    public void addDateHeader(String name, long date) {
        if (isProtected(name)) {
            return;
        }

        super.addDateHeader(name, date);
    }

    @Override
    public void reset() {
        super.reset();
        installCanonical();
    }

    @Override
    public void resetBuffer() {
        super.resetBuffer();
    }

    private boolean isProtected(String name) {
        if (name == null) {
            return false;
        }

        if ((traceIdValue != null) && PlatformHeaders.X_TRACE_ID.equalsIgnoreCase(name)) {
            return true;
        }

        return requestIdValue != null
                && requestIdName != null
                && requestIdName.equalsIgnoreCase(name);
    }
}
