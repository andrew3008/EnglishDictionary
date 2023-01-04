package com.englishDictionary.servicesThirdParty.translates.http;

import org.apache.commons.io.IOUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class HttpConnectionUtils {

    protected static final String DEFAULT_RESPONSE_CHARSET = "ISO-8859-1";
    protected static final String CHARSET_MARK = "charset=";

    private HttpConnectionUtils() {}

    /**
     * Method call without additional headers for possible calls from plugins.
     *
     * @param address URL to post
     * @param params post parameters in Map
     * @return result string returned from server.
     * @throws IOException raises when connection failed.
     */
    public static String post(String address, Map<String, String> params) throws IOException {
        return post(address, params, null);
    }

    /**
     * Post data to the remote URL.
     *
     * @param address
     *            address to post
     * @param params
     *            parameters
     * @param additionalHeaders
     *            additional headers for request, can be null
     * @return Server output
     */
    public static String post(String address, Map<String, String> params,
                              Map<String, String> additionalHeaders) throws IOException {
        URL url = new URL(address);

        ByteArrayOutputStream pout = new ByteArrayOutputStream();
        if (params != null) {
            for (Map.Entry<String, String> p : params.entrySet()) {
                if (pout.size() > 0) {
                    pout.write('&');
                }
                pout.write(p.getKey().getBytes(StandardCharsets.UTF_8));
                pout.write('=');
                pout.write(URLEncoder.encode(p.getValue(), StandardCharsets.UTF_8.name())
                        .getBytes(StandardCharsets.UTF_8));
            }
        }

        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setRequestProperty("Content-Length", Integer.toString(pout.size()));
        if (additionalHeaders != null) {
            for (Map.Entry<String, String> en : additionalHeaders.entrySet()) {
                conn.setRequestProperty(en.getKey(), en.getValue());
            }
        }

        conn.setDoInput(true);
        conn.setDoOutput(true);

        try (OutputStream cout = conn.getOutputStream()) {
            cout.write(pout.toByteArray());
            cout.flush();
            return getStringContent(conn, DEFAULT_RESPONSE_CHARSET);
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Post JSON data to the remote URL.
     *
     * @param address
     *            address to post
     * @param json
     *            JSON-encoded data
     * @param additionalHeaders
     *            additional headers for request, can be null
     * @return Server output
     */
    public static String postJSON(String address, String json,
                                  Map<String, String> additionalHeaders) throws IOException {
        URL url = new URL(address);

        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Content-Length", Integer.toString(json.length()));
        if (additionalHeaders != null) {
            for (Map.Entry<String, String> en : additionalHeaders.entrySet()) {
                conn.setRequestProperty(en.getKey(), en.getValue());
            }
        }

        conn.setDoInput(true);
        conn.setDoOutput(true);

        try (OutputStream cout = conn.getOutputStream()) {
            cout.write(json.getBytes(StandardCharsets.UTF_8));
            cout.flush();
            return getStringContent(conn, "utf-8");
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Parse response as string.
     */
    private static String getStringContent(HttpURLConnection conn, String defaultCharset) throws IOException {
        if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
            throw new ResponseError(conn);
        }
        String contentType = conn.getHeaderField("Content-Type");
        int cp = contentType != null ? contentType.indexOf(CHARSET_MARK) : -1;
        String charset = cp >= 0 ? contentType.substring(cp + CHARSET_MARK.length()) : defaultCharset;

        try (InputStream in = conn.getInputStream()) {
            return IOUtils.toString(in, charset);
        }
    }

    /**
     * HTTP response error storage.
     */
    public static class ResponseError extends IOException {
        public final int code;
        public final String message;
        public final String body;

        public ResponseError(HttpURLConnection conn) throws IOException {
            super(conn.getResponseCode() + ": " + conn.getResponseMessage());
            code = conn.getResponseCode();
            message = conn.getResponseMessage();
            try (InputStream in = conn.getErrorStream()) {
                body = IOUtils.toString(in, StandardCharsets.UTF_8.name());
            }
        }
    }
}

