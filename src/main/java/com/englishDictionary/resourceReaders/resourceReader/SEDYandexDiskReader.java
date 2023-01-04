package com.englishDictionary.resourceReaders.resourceReader;

import com.englishDictionary.config.APIKeys;
import com.englishDictionary.config.Config;
import com.englishDictionary.webServer.utils.SEDHttpClient;
import io.netty.handler.codec.http.HttpResponseStatus;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SEDYandexDiskReader implements SEDReader {

    private final String YANDEX_WEBDAV_URL = "https://webdav.yandex.ru:443/";
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String CONTENT_TYPE_HEADER = "Content-Type";
    private static final String CONTENT_RANGE_HEADER = "Range";
    private static final String ACCEPT_HEADER = "Accept";

    private static List<Integer> successfulHttpStatuses = Arrays.asList(
            HttpResponseStatus.OK.code(),
            HttpResponseStatus.CREATED.code(),
            HttpResponseStatus.ACCEPTED.code(),
            HttpResponseStatus.PARTIAL_CONTENT.code(),
            HttpResponseStatus.MULTI_STATUS.code());

    protected static final String WEBDAV_PROTO_DEPTH = "Depth";
    private static final String WEBDAV_PROPFIND_REQUEST = "PROPFIND";
    private static final String WEBDAV_PROPFIND_REQUEST_CONTENT_LENGTH =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                    "<d:propfind xmlns:d=\"DAV:\">\n" +
                    " <d:prop><d:getcontentlength/></d:prop>\n" +
                    "</d:propfind>";
    public static final String WEBDAV_PROPERTY_GETCONTENTLENGTH = "getcontentlength";

    private String resourceURL;
    private SEDHttpClient httpClient;
    private Map<String, String> headers;
    private long position;

    public SEDYandexDiskReader(String filePath) {
        resourceURL = YANDEX_WEBDAV_URL + filePath;
        httpClient = new SEDHttpClient();
        headers = new HashMap<>();
        headers.put(AUTHORIZATION_HEADER, createAuthorizationHeaderValue(APIKeys.YANDEX_WEBDAV_AUTHORIZATION_TOKEN));
        position = 0;
    }

    @Override
    public long fileLength() {
        Map<String, String> requestHeaders = new HashMap<>(headers);
        requestHeaders.put(WEBDAV_PROTO_DEPTH, "1");
        requestHeaders.put(ACCEPT_HEADER, "*/*");
        requestHeaders.put(CONTENT_TYPE_HEADER, "application/xml; charset=\"utf-8\"");

        SEDHttpClient.HttpRequestResponse response = httpClient.sendRequest(resourceURL, WEBDAV_PROPFIND_REQUEST, requestHeaders, WEBDAV_PROPFIND_REQUEST_CONTENT_LENGTH);
        if (!successfulHttpStatuses.contains(response.getResponseCode())) {
            printResponseErrorMessage(response);
            return -1;
        }

        int fileContentLengh = -1;
        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.IS_COALESCING, Boolean.TRUE);
        XMLStreamReader xmlReader = null;
        boolean isGetContentLengthElement = false;
        try {
            xmlReader = factory.createXMLStreamReader(new ByteArrayInputStream(response.getContent()));
            int event = xmlReader.getEventType();
            boolean notAllValuesFound = true;
            while (true) {
                switch (event) {
                    case XMLStreamConstants.START_ELEMENT:
                        isGetContentLengthElement = WEBDAV_PROPERTY_GETCONTENTLENGTH.equals(xmlReader.getLocalName());
                        break;

                    case XMLStreamConstants.CHARACTERS:
                        if (xmlReader.isWhiteSpace()) {
                            break;
                        }
                        if (isGetContentLengthElement) {
                            fileContentLengh = Integer.valueOf(xmlReader.getText().trim()).intValue();
                            notAllValuesFound = false;
                        }
                        break;
                }
                if (!notAllValuesFound || !xmlReader.hasNext()) {
                    break;
                }
                event = xmlReader.next();
            }
        } catch (XMLStreamException e) {
            e.printStackTrace();
        } finally {
            if (xmlReader != null) {
                try {
                    xmlReader.close();
                } catch (XMLStreamException e) {
                    e.printStackTrace();
                }
            }
        }
        return fileContentLengh;
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
