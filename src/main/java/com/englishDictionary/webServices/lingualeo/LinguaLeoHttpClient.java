package com.englishDictionary.webServices.lingualeo;

import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.cookie.Cookie;
import org.apache.http.impl.client.BasicCookieStore;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;

import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Created by Andrew on 9/20/2016.
 */
public class LinguaLeoHttpClient {
    private BasicCookieStore httpCookieStore;
    private HttpClient httpClient;
    private LinguaLeoApiSecurityCooker securityCooker;

    public LinguaLeoHttpClient() {
        httpCookieStore = new BasicCookieStore();
        httpClient = HttpClientBuilder.create().setDefaultCookieStore(httpCookieStore).build();
    }

    public String sendGetRequest(String resourceURL) {
        HttpGet get = new HttpGet(resourceURL);
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

    public void buildSecurityCooker() {
        securityCooker = new LinguaLeoApiSecurityCooker();
        for (Cookie cookie : httpCookieStore.getCookies()) {
            if (cookie.getName().equals("lingualeouid")) {
                securityCooker.setLinguaLeoUID(cookie.getValue());
            } else if (cookie.getName().equals("lang")) {
                securityCooker.setLang(cookie.getValue());
            } else if (cookie.getName().equals("userid")) {
                securityCooker.setUserId(cookie.getValue());
            } else if (cookie.getName().equals("servid")) {
                securityCooker.setServid(cookie.getValue());
            } else if (cookie.getName().equals("remember")) {
                securityCooker.setRemember(cookie.getValue());
            } else if (cookie.getName().equals("firstseen")) {
                securityCooker.setFirstSeen(cookie.getValue());
            }
        }
        //System.out.println("[securityCooker]: " + securityCooker.build());
    }

    public String sendPostRequest(String resourceURL, Map<String, String> params) {
        if (securityCooker == null) {
            // TODO: Send to client Internel_Server_Err with message "Not authetorization yet"
            return null;
        }

        HttpPost post = new HttpPost(resourceURL);
        HttpResponse response = null;
        int code = -1;
        try {
            post.addHeader("Content-Type", "application/x-www-form-urlencoded");
            post.addHeader("X-Requested-With", "XMLHttpRequest");
            post.addHeader("LinguaLeo-Version", "2.0.3.3");
            post.addHeader("X-Accept-Language", securityCooker.getLang());
            post.addHeader("Cookie", securityCooker.build());

            List<NameValuePair> entityParams = new ArrayList<>();
            for (Map.Entry<String, String> entry : params.entrySet()) {
                entityParams.add(new BasicNameValuePair(entry.getKey(), entry.getValue()));
            }
            post.setEntity(new UrlEncodedFormEntity(entityParams, "UTF-8"));

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

    // ------------------------------------------------------------------------------------------------

    private static final String JSON = "application/json";

    private String handleResponse(HttpResponse response) {
        String contentType = null;

        if (response.getEntity().getContentType() != null) {
            contentType = response.getEntity().getContentType().getValue();
            //System.out.println("[OAuthUtils] handleResponse:[" + contentType + "]");
        }

        if (contentType.contains(JSON)) {
            return handleJsonResponse(response);
        }

        return null;
    }

    private static String handleJsonResponse(HttpResponse response) {
        try {
            String result = EntityUtils.toString(response.getEntity());
            //System.out.println("JsonResponse: [" + result);
            new PrintStream(System.out, true, "UTF-8").println("JsonResponse: [" + result);
            return result;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }
}
