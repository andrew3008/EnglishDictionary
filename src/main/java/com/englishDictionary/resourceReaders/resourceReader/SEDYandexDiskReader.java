package com.englishDictionary.resourceReaders.resourceReader;

import com.englishDictionary.config.Config;
import com.englishDictionary.webServer.utils.SEDHttpClient;
import io.netty.handler.codec.http.HttpResponseStatus;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SEDYandexDiskReader implements SEDReader {

    private final String YANDEX_WEBDAV_URL = "http://webdav.yandex.ru/";
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String CONTENT_RANGE_HEADER = "Range";
    private static List<HttpResponseStatus> successfulHttpStatuses = Arrays.asList(
            HttpResponseStatus.OK,
            HttpResponseStatus.CREATED,
            HttpResponseStatus.ACCEPTED,
            HttpResponseStatus.PARTIAL_CONTENT);

    private String resourceURL;
    private SEDHttpClient httpClient;
    private Map<String, String> headers;
    private long position;

    public SEDYandexDiskReader(String filePath) {
        resourceURL = YANDEX_WEBDAV_URL + filePath;
        httpClient = new SEDHttpClient();
        headers = new HashMap<>();
        headers.put(AUTHORIZATION_HEADER, createAuthorizationHeaderValue(Config.YANDEX_WEBDAV_AUTHORIZATION_TOKEN));
        position = 0;
    }

    @Override
    public long fileLength() {
        Map<String, String> requestHeaders = new HashMap<>(headers);
        requestHeaders.put("Depth", "1");
        requestHeaders.put("Accept", "*/*");
        requestHeaders.put("Content-Type", "application/x-www-form-urlencoded");

        String body = "<?xml version=\"1.0\" encoding=\"utf-8\" ?>\n" +
                "<propfind xmlns=\"DAV:\">\n" +
                "  <prop>\n" +
                "    <myprop xmlns=\"mynamespace\"/>\n" +
                "  </prop>\n" +
                "</propfind>";

        SEDHttpClient.HttpRequestResponse response = httpClient.sendRequest(resourceURL, "PROPFIND", headers, body);
        String responseContent = SEDHttpClient.responseContentToString(response);
        if (!successfulHttpStatuses.contains(response.getResponseCode())) {
            printResponseErrorMessage(response);
            return -1;
        }
        //String responseContent = SEDHttpClient.responseContentToString(response);
        return -1;
    }

    @Override
    public int read() throws IOException {
        return read(new byte[1]);
    }

    @Override
    public int read(byte[] b) throws IOException {
        return read(b, 0, b.length);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        headers.put(CONTENT_RANGE_HEADER, createRangeHeaderValue(position, len));
        SEDHttpClient.HttpRequestResponse response = httpClient.sendGetRequest(resourceURL, headers);
        if (!successfulHttpStatuses.contains(response.getResponseCode())) {
            printResponseErrorMessage(response);
            return -1;
        }
        System.arraycopy(response.getContent(), 0, b, off, response.getContentLength());
        position += response.getContentLength();
        return response.getContentLength();
    }

    @Override
    public int read(OutputStream outputStream, int len) throws IOException {
        headers.put(CONTENT_RANGE_HEADER, createRangeHeaderValue(position, len));
        SEDHttpClient.HttpRequestResponse response = httpClient.sendGetRequest(resourceURL, headers, outputStream);
        if (!successfulHttpStatuses.contains(response.getResponseCode())) {
            printResponseErrorMessage(response);
            return -1;
        }
        position += response.getContentLength();
        return response.getContentLength();
    }

    private String createAuthorizationHeaderValue(String token) {
        return "OAuth " + token;
    }

    private String createRangeHeaderValue(long position, int len) {
        StringBuilder contentRange = new StringBuilder();
        contentRange.append("bytes=").append(position).append("-").append(position + len - 1);
        return contentRange.toString();
    }

    private void printResponseErrorMessage(SEDHttpClient.HttpRequestResponse response) {
        System.out.println("[" + this.getClass().getName() + "] responseErrorMessage: [" + response.getResponseCode() + "] " + HttpResponseStatus.valueOf(response.getResponseCode()).reasonPhrase());
    }

    @Override
    public void seek(long position) throws IOException {
        this.position = position;
    }

    @Override
    public void close() throws IOException {
    }

}
