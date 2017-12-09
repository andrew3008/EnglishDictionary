package com.englishDictionary.webServer.utils;

import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.BasicCookieStore;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;

import java.io.IOException;
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

    public String sendGetRequest(String resourceURL) {
        return sendGetRequest(resourceURL, null);
    }

    public String sendGetRequest(String resourceURL, Map<String, String> headers) {
        HttpGet get = new HttpGet(resourceURL);
        if (headers != null) {
            for (Map.Entry<String, String> headersEntry : headers.entrySet()) {
                get.addHeader(headersEntry.getKey(), headersEntry.getValue());
            }
        }

        HttpResponse response = null;
        int code = -1;
        try {
            response = httpClient.execute(get);
            code = response.getStatusLine().getStatusCode();
                    /*if (code >= 400) {
                        throw new RuntimeException(
								"Could not access protected resource. Server returned http code: "
										+ code);

					}*/

				/*} else {
                    throw new RuntimeException(
							"Could not regenerate access token");
				}*/

            return EntityUtils.toString(response.getEntity());
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } finally {
            get.releaseConnection();
        }
        return null;
    }

    public String sendPostRequest(String resourceURL, Map<String, String> headers, Map<String, String> params) {
        HttpPost post = new HttpPost(resourceURL);
        for (Map.Entry<String, String> headersEntry : headers.entrySet()) {
            post.addHeader(headersEntry.getKey(), headersEntry.getValue());
        }

        List<NameValuePair> entityParams = new ArrayList<>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            entityParams.add(new BasicNameValuePair(entry.getKey(), entry.getValue()));
        }
        try {
            post.setEntity(new UrlEncodedFormEntity(entityParams, "UTF-8"));
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
            return null;
        }

        HttpResponse response = null;
        int code = -1;
        try {
            response = httpClient.execute(post);
            code = response.getStatusLine().getStatusCode();
                    /*if (code >= 400) {
                        throw new RuntimeException(
								"Could not access protected resource. Server returned http code: "
										+ code);

					}*/

				/*} else {
                    throw new RuntimeException(
							"Could not regenerate access token");
				}*/

            //return handleResponse(response);
            //System.out.println("[Post] response:[" + response + "]");
            //handleResponse(response);
            return EntityUtils.toString(response.getEntity());
        } catch (ClientProtocolException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } finally {
            post.releaseConnection();
        }

        return null;
    }

}
