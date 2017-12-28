package com.englishDictionary.webServer;

import io.netty.handler.codec.http.HttpResponseStatus;

import java.util.UUID;
import java.util.HashMap;
import java.util.Map;

/**
 * Created by Andrew on 9/3/2016.
 */
public class HttpServletResponse implements Comparable<HttpServletResponse> {

    private static int INIT_RESPONSE_OUTPUT_STREAM_SIZE = 8388608; // 8 MB

    private String id;
    private ByteArrayOutputStream outputStream;
    private String contentType;
    private int presetContentLength;
    private HttpResponseStatus status;
    private String errorMessage;
    private String internalRedirectURL;
    private String externalRedirectURL;
    private Map<String, String> headers;
    private boolean forceClosed;

    public HttpServletResponse() {
        id = UUID.randomUUID().toString();
        headers = new HashMap<>();
        presetContentLength = -1;
        outputStream = new ByteArrayOutputStream(INIT_RESPONSE_OUTPUT_STREAM_SIZE);
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public ByteArrayOutputStream getOutputStream() {
        return outputStream;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public int getPresetContentLength() {
        return presetContentLength;
    }

    public void setPresetContentLength(int presetContentLength) {
        this.presetContentLength = presetContentLength;
    }

    public HttpResponseStatus getStatus() {
        return status;
    }

    public void setStatus(HttpResponseStatus status) {
        this.status = status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getInternalRedirectURL() {
        return internalRedirectURL;
    }

    public void setInternalRedirectURL(String internalRedirectURL) {
        this.internalRedirectURL = internalRedirectURL;
    }

    public String getExternalRedirectURL() {
        return externalRedirectURL;
    }

    public void setExternalRedirectURL(String externalRedirectURL) {
        this.externalRedirectURL = externalRedirectURL;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public void addHeader(String name, String value) {
        headers.put(name, value);
    }

    public boolean isForceClosed() {
        return forceClosed;
    }

    public void setForceClosed(boolean forceClosed) {
        this.forceClosed = forceClosed;
    }

    @Override
    public int compareTo(HttpServletResponse anotherResponse) {
        return id.compareTo(anotherResponse.getId());
    }

}
