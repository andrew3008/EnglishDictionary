package com.englishDictionary.resourceReaders.htmlDatFile.resourceReader;

import com.englishDictionary.config.Config;
import com.englishDictionary.webServer.utils.SEDHttpClient;
import io.netty.handler.codec.http.HttpResponseStatus;

import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

public class SEDYandexDiskReader implements SEDReader {

    private final String YANDEX_WEBDAV_URL = "http://webdav.yandex.ru/";
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String CONTENT_RANGE_HEADER = "Range";

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
    public int read(byte[] b) throws IOException {
        return read(b, 0, b.length);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        headers.put(CONTENT_RANGE_HEADER, createRangeHeaderValue(position, len));
        SEDHttpClient.HttpRequestResponse response = httpClient.sendGetRequest(resourceURL, headers);
        System.arraycopy(response.getContent(), 0, b, off, response.getContentLength());
        position += response.getContentLength();
        if (response.getResponseCode() != HttpResponseStatus.CREATED.code() || response.getResponseCode() != HttpResponseStatus.CREATED.code()) {
            printResponseErrorMessage(response);
        }
        return response.getContentLength();
    }

    @Override
    public int read(OutputStream outputStream, int len) throws IOException {
        headers.put(CONTENT_RANGE_HEADER, createRangeHeaderValue(position, len));
        SEDHttpClient.HttpRequestResponse response = httpClient.sendGetRequest(resourceURL, headers, outputStream);
        position += response.getContentLength();
        if (response.getResponseCode() != HttpResponseStatus.CREATED.code() || response.getResponseCode() != HttpResponseStatus.CREATED.code()) {
            printResponseErrorMessage(response);
        }
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
