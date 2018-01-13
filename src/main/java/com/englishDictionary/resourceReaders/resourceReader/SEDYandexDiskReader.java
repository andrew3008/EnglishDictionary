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

    private final String YANDEX_WEBDAV_URL = "https://webdav.yandex.ru/";
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String CONTENT_RANGE_HEADER = "Range";
    private static List<Integer> successfulHttpStatuses = Arrays.asList(
            HttpResponseStatus.OK.code(),
            HttpResponseStatus.CREATED.code(),
            HttpResponseStatus.ACCEPTED.code(),
            HttpResponseStatus.PARTIAL_CONTENT.code(),
            HttpResponseStatus.MULTI_STATUS.code());

    protected static final String userAgent = "Webdav Android Client Example/1.0";
    protected static final String LOCATION_HEADER = "Location";
    public static final String NO_REDIRECT_CONTEXT = "yandex.no-redirect";
    protected static final String WEBDAV_PROTO_DEPTH = "Depth";

    private String resourceURL;
    private SEDHttpClient httpClient;
    private Map<String, String> headers;
    private long position;

    private String filePath;

    public SEDYandexDiskReader(String filePath) {
        this.filePath = filePath;
        resourceURL = YANDEX_WEBDAV_URL + filePath;
        httpClient = new SEDHttpClient();
        headers = new HashMap<>();
        headers.put(AUTHORIZATION_HEADER, createAuthorizationHeaderValue(Config.YANDEX_WEBDAV_AUTHORIZATION_TOKEN));
        position = 0;
    }

    //https://tech.yandex.com/disk/doc/dg/reference/propfind_property-request-docpage/#propfind_property-request
    //https://github.com/nixsolutions/yandex-php-library/blob/master/src/Yandex/Disk/DiskClient.php

    //https://github.com/nixsolutions/yandex-php-library/blob/master/src/Yandex/Disk/DiskClient.php
    //https://github.com/pfumagalli/webdav/blob/master/src/main/java/it/could/webdav/methods/PROPFIND.java

    // объем занятого/свободного места на Диске (согласно RFC 4331);
    //https://tools.ietf.org/html/rfc4918
    //https://tech.yandex.ru/disk/doc/dg/reference/propfind-docpage/#propfind/
    //https://tech.yandex.ru/disk/doc/dg/reference/property-request-docpage/#property-request

    //https://github.com/dmfs/jdav-client
    /*PropFind request = new PropFind(davContext, Depth.one);
request.addProperties(WebDav.Properties.DISPLAYNAME,
    WebDav.Properties.RESOURCETYPE,
    WebDav.Properties.GETETAG);*/

    /*PROPFIND. Used to fetch one or more properties belonging to one or more resources. When a client submits a PROPFIND request on a collection to the server, the request may include a Depth: header with a value of 0, 1, or infinity.

            0. Specifies that the properties of the collection at the specified URI will be fetched.

            1. Specifies that the properties of the collection and resources immediately under the specified URI will be fetched.

    infinity. Specifies that the properties of the collection and all member URIs it contains will be fetched. Be aware that because a request with infinite depth will crawl the entire collection, it can impose a large burden on the server.*/

    // https://yandex.ru/support/webmaster/error-dictionary/http-codes.html

    //WEBDAV_PROTO_DEPTH

//    private static final String PROPFIND_REQUEST =
//            "<?xml version='1.0' encoding='utf-8' ?>" +
//                    "<d:propfind xmlns:d='DAV:'>" +
//                    "<d:prop xmlns:m='urn:yandex:disk:meta'>" +
//                    "<d:resourcetype/>" +
//                    "<d:displayname/>" +
//                    "<d:getcontentlength/>" +
//                    "<d:getlastmodified/>" +
//                    "<d:getetag/>" +
//                    "<d:getcontenttype/>" +
//                    "<m:alias_enabled/>" +
//                    "<m:visible/>" +
//                    "<m:shared/>" +
//                    "<m:readonly/>" +
//                    "<m:public_url/>" +
//                    "<m:etime/>" +
//                    "<m:mediatype/>" +
//                    "<m:mpfs_file_id/>" +
//                    "<m:hasthumbnail/>" +
//                    "</d:prop>" +
//                    "</d:propfind>";

//    private static final String PROPFIND_REQUEST =
//    "<D:propfind xmlns:D=\"DAV:\">\n"+
//            "  <D:prop>\n"+
//            "    <D:quota-available-bytes/>\n"+
//            "    <D:quota-used-bytes/>\n"+
//            "  </D:prop>\n"+
//            "</D:propfind>";

    private static final String PROPFIND_REQUEST =
    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"+
            "<d:propfind xmlns:d=\"DAV:\">\n"+
//            " <d:prop><d:displayname/></d:prop>\n"+
            " <d:prop><d:getcontentlength/></d:prop>\n"+
//            " <d:prop><d:resourcetype/></d:prop>\n"+
//            " <d:prop><d:iscollection/></d:prop>\n"+
            "</d:propfind>";

//    private static final String PROPFIND_REQUEST =
//            "<?xml version='1.0' encoding='utf-8'?>"
//            + "<propfind xmlns='DAV:'>"
//            + "<allprop/>"
//            + "</propfind>";


    //https://github.com/pentaho/pdi-vfs/blob/master/core/src/main/java/org/apache/commons/vfs/provider/webdav/WebdavFileObject.java
    /**
     * Returns the size of the file content (in bytes).
     */
//    protected long doGetContentSize() throws Exception
//    {
//        DavProperty property = getProperty((URLFileName) getName(),
//                DavConstants.PROPERTY_GETCONTENTLENGTH);
//        if (property != null)
//        {
//            String value = (String) property.getValue();
//            return Long.parseLong(value);
//        }
//        return 0;
//    }

    //https://jackrabbit.apache.org/api/2.6/constant-values.html#org.apache.jackrabbit.webdav.DavConstants.PROPERTY_GETCONTENTLENGTH
    public static final String	PROPERTY_GETCONTENTLENGTH =	"getcontentlength";

    @Override
    public long fileLength() {
        Map<String, String> requestHeaders = new HashMap<>(headers);
        requestHeaders.put(WEBDAV_PROTO_DEPTH, "1");
        requestHeaders.put("Accept", "*/*");
        //requestHeaders.put("Content-Type", "application/x-www-form-urlencoded");
        requestHeaders.put("Content-Type", "application/xml; charset=\"utf-8\"");

//        String body = "<?xml version=\"1.0\" encoding=\"utf-8\" ?>\n" +
//                "<propfind xmlns=\"DAV:\">\n" +
//                "<prop xmlns:R=\"http://ns.example.com/boxschema/\">\n" +
//                "<R:bigbox/>
//        "<R:author/>
//        "<R:DingALing/>
//        "<R:Random/>
//        "</D:prop>
//                "</propfind>\n";
        //String body = "<propfind xmlns=\"DAV:\"><prop><public_url xmlns=\"urn:yandex:disk:meta\"/></prop></propfind>";

        String body = PROPFIND_REQUEST;
        //String body = "";
//        requestHeaders.put("Content-Length", Integer.valueOf(body.getBytes().length).toString());

        SEDHttpClient.HttpRequestResponse response = httpClient.sendRequest(resourceURL, "PROPFIND", requestHeaders, body);
        //SEDHttpClient.HttpRequestResponse response = httpClient.sendRequest(resourceURL, "PROPPATCH", headers, body);
//        SEDHttpClient.HttpRequestResponse response = httpClient.sendRequest(YANDEX_WEBDAV_URL, "PROPPATCH", headers, body);
        String responseContent = SEDHttpClient.responseContentToString(response);
        if (!successfulHttpStatuses.contains(response.getResponseCode())) {
            printResponseErrorMessage(response);
            return -1;
        }
        //String responseContent = SEDHttpClient.responseContentToString(response);
        return 6085880;
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
