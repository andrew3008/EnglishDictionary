package com.englishDictionary.webServer.utils;

import com.englishDictionary.config.Config;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.cookie.Cookie;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.BasicCookieStore;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SEDHttpClient {

    private HttpClient httpClient;
    private BasicCookieStore httpCookieStore;

    public SEDHttpClient() {
        httpCookieStore = new BasicCookieStore();
        httpClient = HttpClientBuilder.create().setDefaultCookieStore(httpCookieStore).build();
    }

    public BasicCookieStore getHttpCookieStore() {
        return httpCookieStore;
    }

    public void printHttpCookieStore() {
        for (Cookie cookie : httpCookieStore.getCookies()) {
            System.out.println("cookieName:" + cookie.getName() + ", cookieValue:" + cookie.getValue());
        }
    }

    public HttpRequestResponse sendRequest(String resourceURL, String method, Map<String, String> headers, String body) {
        RequestBuilder requestBuilder = RequestBuilder.create(method);
        requestBuilder.setUri(resourceURL);
        if (headers != null) {
            for (Map.Entry<String, String> headersEntry : headers.entrySet()) {
                requestBuilder.addHeader(headersEntry.getKey(), headersEntry.getValue());
            }
        }
        StringEntity entity = null;
        try {
            entity = new StringEntity(body);
            requestBuilder.setEntity(entity);
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
            return null;
        }

        HttpResponse response = executeRequest(requestBuilder.build());
        byte[] responseBody = null;
        if (response != null) {
            try {
                responseBody = EntityUtils.toByteArray(response.getEntity());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return new HttpRequestResponse(responseBody, getHttpResponseContentLength(response), getHttpResponseCode(response));
    }

    public HttpRequestResponse sendGetRequest(String resourceURL) {
        return sendGetRequest(resourceURL, null);
    }

    public HttpRequestResponse sendGetRequest(String resourceURL, Map<String, String> headers) {
        HttpGet get = new HttpGet(resourceURL);
        if (headers != null) {
            for (Map.Entry<String, String> headersEntry : headers.entrySet()) {
                get.addHeader(headersEntry.getKey(), headersEntry.getValue());
            }
        }

        HttpResponse response = executeRequest(get);
        if (response == null) {
            get.releaseConnection();
            return new HttpRequestResponse(null, getHttpResponseContentLength(response), getHttpResponseCode(response));
        }

        byte[] responseBody = null;
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            response.getEntity().writeTo(baos);
            responseBody = baos.toByteArray();
        } catch (IOException e) {
            e.printStackTrace();
        }
        get.releaseConnection();
        return new HttpRequestResponse(responseBody, responseBody.length, getHttpResponseCode(response));
    }

    public HttpRequestResponse sendGetRequest(String resourceURL, Map<String, String> headers, OutputStream outputStream) {
        HttpGet get = new HttpGet(resourceURL);
        if (headers != null) {
            for (Map.Entry<String, String> headersEntry : headers.entrySet()) {
                get.addHeader(headersEntry.getKey(), headersEntry.getValue());
            }
        }

        HttpResponse response = executeRequest(get);
        if (response != null) {
            try {
                response.getEntity().writeTo(outputStream);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        get.releaseConnection();
        return new HttpRequestResponse(null, getHttpResponseContentLength(response), getHttpResponseCode(response));
    }

    public HttpRequestResponse sendPostRequest(String resourceURL) {
        return sendPostRequest(resourceURL, null, null);
    }

    public HttpRequestResponse sendPostRequest(String resourceURL, Map<String, String> headers) {
        return sendPostRequest(resourceURL, headers, null);
    }

    public HttpRequestResponse sendPostRequest(String resourceURL, Map<String, String> headers, Map<String, String> params) {
        HttpPost post = new HttpPost(resourceURL);
        for (Map.Entry<String, String> headersEntry : headers.entrySet()) {
            post.addHeader(headersEntry.getKey(), headersEntry.getValue());
        }

        List<NameValuePair> entityParams = new ArrayList<>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            entityParams.add(new BasicNameValuePair(entry.getKey(), entry.getValue()));
        }
        try {
            post.setEntity(new UrlEncodedFormEntity(entityParams, Config.CHARSET));
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
            return null;
        }

        HttpResponse response = executeRequest(post);
        if (response == null) {
            post.releaseConnection();
            return new HttpRequestResponse(null, getHttpResponseContentLength(response), getHttpResponseCode(response));
        }

        byte[] responseBody = null;
        try {
            responseBody = EntityUtils.toByteArray(response.getEntity());
        } catch (IOException e) {
            e.printStackTrace();
        }
        post.releaseConnection();
        return new HttpRequestResponse(responseBody, getHttpResponseContentLength(response), getHttpResponseCode(response));
    }

    private HttpResponse executeRequest(HttpUriRequest httpRequest) {
        try {
            return httpClient.execute(httpRequest);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    public static String responseContentToString(HttpRequestResponse response) {
        if (response.getContent() == null) {
            return null;
        }

        try {
            return new String(response.getContent(), Config.CHARSET);
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        return null;
    }

    private int getHttpResponseContentLength(HttpResponse response) {
        return (response == null) ? -1 : (int) response.getEntity().getContentLength();
    }

    private int getHttpResponseCode(HttpResponse response) {
        return (response == null) ? -1 : response.getStatusLine().getStatusCode();
    }

    public class HttpRequestResponse {
        private byte[] content;
        private int contentLength;
        private int responseCode;

        public HttpRequestResponse(byte[] content, int contentLength, int responseCode) {
            this.content = content;
            this.contentLength = contentLength;
            this.responseCode = responseCode;
        }

        public byte[] getContent() {
            return content;
        }

        public int getContentLength() {
            return contentLength;
        }

        public int getResponseCode() {
            return responseCode;
        }
    }

}
