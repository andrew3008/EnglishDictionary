package com.englishDictionary.servicesThirdParty.lingualeo;

import com.englishDictionary.webServer.utils.SEDHttpClient;
import org.apache.http.cookie.Cookie;

import java.util.HashMap;
import java.util.Map;

/**
 * Created by Andrew on 9/20/2016.
 */
public class LinguaLeoHttpClient {

    private SEDHttpClient sedHttpClient;
    private LinguaLeoApiSecurityCooker securityCooker;

    public LinguaLeoHttpClient() {
        sedHttpClient = new SEDHttpClient();
    }

    public SEDHttpClient.HttpRequestResponse sendGetRequest(String resourceURL) {
        return sedHttpClient.sendGetRequest(resourceURL);
    }

    public void buildSecurityCooker() {
        securityCooker = new LinguaLeoApiSecurityCooker();
        for (Cookie cookie : sedHttpClient.getHttpCookieStore().getCookies()) {
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
        // TODO: Add the flag for debug
        //System.out.println("[securityCooker]: " + securityCooker.build());
    }

    public SEDHttpClient.HttpRequestResponse sendPostRequest(String resourceURL, Map<String, String> params) {
        if (securityCooker == null) {
            // TODO: Send to client Internel_Server_Err with message "Not authetorization yet"
            return null;
        }

        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/x-www-form-urlencoded");
        headers.put("X-Requested-With", "XMLHttpRequest");
        headers.put("LinguaLeo-Version", "2.0.3.3");
        headers.put("X-Accept-Language", securityCooker.getLang());
        headers.put("Cookie", securityCooker.build());
        return sedHttpClient.sendPostRequest(resourceURL, headers, params);
    }

}
