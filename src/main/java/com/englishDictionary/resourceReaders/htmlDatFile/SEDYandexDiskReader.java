package com.englishDictionary.resourceReaders.htmlDatFile;

import com.englishDictionary.webServer.utils.SEDHttpClient;

import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

public class SEDYandexDiskReader implements SEDReader {

    private final String YANDEX_WEBDAV_URL = "http://webdav.yandex.ru/";
    private static final String CONTENT_RANGE_HEADER = "Range";

    private String resourceURL;
    private SEDHttpClient httpClient;
    private Map<String, String> headers;
    private long position;

    public SEDYandexDiskReader(String fileName) {
        resourceURL = YANDEX_WEBDAV_URL + fileName;
        httpClient = new SEDHttpClient();
        headers = new HashMap<>();
        headers.put("Authorization", "OAuth AQAEA7qgySSkAAS9YffJNgqU1k9qp75Zd9Dq4WY");
        position = 0;
    }

    @Override
    public int read(byte[] b) throws IOException {
        return read(b, 0, b.length);
    }

    //Credentials.AUTHORIZATION_HEADER

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        StringBuilder contentRange = new StringBuilder();
        contentRange.append("bytes=").append(position).append("-").append(position + len - 1);
        headers.put(CONTENT_RANGE_HEADER, contentRange.toString());
        byte[] responseBytes = httpClient.sendGetRequestBytes(resourceURL, headers);
        int responseBodyLength = responseBytes.length;
        System.arraycopy(responseBytes, 0, b, off, responseBodyLength);
        position += responseBodyLength;
        return responseBodyLength;
    }

    @Override
    public int read(OutputStream outputStream, int len) throws IOException {
        StringBuilder contentRange = new StringBuilder();
        contentRange.append("bytes=").append(position).append("-").append(position + len - 1);
        headers.put(CONTENT_RANGE_HEADER, contentRange.toString());
        int responseBodyLength = httpClient.sendGetRequestBytes(resourceURL, headers, outputStream);
        position += responseBodyLength;
        return responseBodyLength;
    }

    /*int code = response.code();

        switch (code) {
        case 201:
        case 202:
            logger.debug("uploadFile: file uploaded successfully: "+file);
            break;
        case 404:
            throw new NotFoundException(code, null);
        case 409:
            throw new ConflictException(code, null);
        case 412:
            throw new PreconditionFailedException(code, null);
        case 413:
            throw new FileTooBigException(code, null);
        case 503:
            throw new ServiceUnavailableException(code, null);
        case 507:
            throw new InsufficientStorageException(code, null);
        default:
            throw new HttpCodeException(code);
    }*/

    @Override
    public void seek(long position) throws IOException {
        this.position = position;
    }

    @Override
    public void close() throws IOException {
    }

}
