package com.englishDictionary.resourceReaders.htmlDatFile;

import com.englishDictionary.webServer.utils.SEDHttpClient;

import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

public class SEDYandexDiskReader implements SEDReader {

    private final String YANDEX_WEBDAV_URL = "http://webdav.yandex.ru/";
    private static final String CONTENT_RANGE_HEADER = "Content-Range";
//    private static final String CONTENT_RANGE_HEADER = "Range";

    private String resourceURL;
    private SEDHttpClient httpClient;
    private Map<String, String> headers;
    private long position;

    public SEDYandexDiskReader(String fileName) {
        resourceURL = YANDEX_WEBDAV_URL + fileName;
        //resourceURL = "https://content.googleapis.com/drive/v3/files/";
//        resourceURL = "https://content.googleapis.com/drive/v3/files/1T7S4Zx94uTual7HVRCYHfRUcvRJeDRzi";
        httpClient = new SEDHttpClient();
        headers = new HashMap<>();
        //headers.put("Cookie", "yandexuid=137029991514971623;");
        //headers.put("Accept", "*/*");
        headers.put("Authorization", "OAuth AQAEA7qgySSkAAS9YffJNgqU1k9qp75Zd9Dq4WY");

        //token:AC4w5VjBsCTx1T5riPuUwltORRN-ZKFv6w:1515140199755

//        headers.put("Accept", "*/*");
//        headers.put("Authorization", "Bearer ya29.Gls5BY8lR4bTlpr2qrzq6scIBnuq6io5qPJFByWOOrLfZ6xprsQ5yCzIg_1Okp_LqMVQ_e97w-mKn9pOntHQi3JuIumdZsSJ91SQPq0QeCoUO2097QHRj89LWiGt");
        position = 0;
    }

    @Override
    public int read(byte[] b) throws IOException {
        return read(b, 0, b.length);
    }

    //Credentials.AUTHORIZATION_HEADER

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        //System.out.println("[SEDYandexDiskReader][read] len:" + len);
        StringBuilder contentRange = new StringBuilder();
        //contentRange.append("bytes ").append(position).append("-").append((position + len) == 0 ? 0 : position + len - 1).append("/").append(len);
//        contentRange.append(position).append("-").append((position + len) == 0 ? 0 : position + len - 1);
        //headers.put(CONTENT_RANGE_HEADER, contentRange.toString());
        //headers.put("Range", "bytes=" + String.valueOf(position) + "-"
        //contentRange.append("bytes=").append(position).append("-").append((position + len) == 0 ? 0 : position + len - 1).append("/").append(len);
        //headers.put("Range", contentRange.toString());
        //contentRange.append("bytes=").append(position).append("-").append((position + len) == 0 ? 0 : position + len - 1);
        contentRange.append("bytes=").append(position).append("-").append(position + len - 1);
        headers.put("Range", contentRange.toString());
//        headers.put(CONTENT_RANGE_HEADER, "bytes 1-4/5");
        //headers.put(CONTENT_RANGE_HEADER, "bytes=0-3");
//        headers.put("Range", "bytes=" + position + "-" + ((position + len) == 0 ? 0 : position + len - 1));
        //System.out.println("[SEDYandexDiskReader][read] Content-Range:" + contentRange.toString());
        //System.out.println("[SEDYandexDiskReader][read] resourceURL:" + resourceURL);
        byte[] responseBytes = httpClient.sendGetRequestBytes(resourceURL, headers);
        //InputStream responseInputStream = httpClient.sendGetRequestBytes(resourceURL, headers);
//        responseInputStream.read(b, off, len);
        //System.out.println("[SEDYandexDiskReader][read] response:" + response);
        //byte[] responseBytes = response.getBytes();
        int responseBytesLength = responseBytes.length;
        //System.out.println("[SEDYandexDiskReader][read] responseBytesLength:" + responseBytesLength);
        System.arraycopy(responseBytes, 0, b, off, responseBytesLength);
//        System.arraycopy(responseBytes, 0, b, off, len);
        position += responseBytesLength;
        //System.out.println("[SEDYandexDiskReader][read] position:" + position);
//        return len;
        return responseBytesLength;
    }

    @Override
    public int read(OutputStream outputStream, int len) throws IOException {
        StringBuilder contentRange = new StringBuilder();
        contentRange.append("bytes=").append(position).append("-").append(position + len - 1);
        headers.put("Range", contentRange.toString());
        int responseBytesLength = httpClient.sendGetRequestBytes(resourceURL, headers, outputStream);
        position += responseBytesLength;
        return responseBytesLength;
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
