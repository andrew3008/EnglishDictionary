package com.englishDictionary.servicesThirdParty.quizlet;

import com.englishDictionary.webServer.HttpServletRequest;
import com.englishDictionary.webServer.HttpServletRequestJavax;
import com.englishDictionary.webServer.HttpServletResponse;
import org.apache.oltu.oauth2.client.OAuthClient;
import org.apache.oltu.oauth2.client.URLConnectionClient;
import org.apache.oltu.oauth2.client.request.OAuthClientRequest;
import org.apache.oltu.oauth2.client.response.OAuthAuthzResponse;
import org.apache.oltu.oauth2.client.response.OAuthJSONAccessTokenResponse;
import org.apache.oltu.oauth2.common.exception.OAuthProblemException;
import org.apache.oltu.oauth2.common.exception.OAuthSystemException;
import org.apache.oltu.oauth2.common.message.types.GrantType;
import org.apache.oltu.oauth2.common.message.types.ResponseType;

public class Auth2Client {

    public static String getAuthenticationURL() {
        OAuthClientRequest.AuthenticationRequestBuilder builder = OAuthClientRequest
                .authorizationLocation(Auth2ClientAccount.QUIZLET_AUTHORIZE_URL)
                .setResponseType(ResponseType.CODE.name().toLowerCase())
                .setClientId(Auth2ClientAccount.CLIENT_ID)
                .setScope(Auth2ClientAccount.SCOPE)
                .setState(Auth2ClientAccount.STATE);

        OAuthClientRequest authRequest;
        try {
            authRequest = builder.buildQueryMessage();
        } catch (OAuthSystemException e) {
            e.printStackTrace();
            //response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return null;
        }

        return authRequest.getLocationUri();
    }

    public static String parseAuthenticationResponse(HttpServletRequest request, HttpServletResponse response) {
        OAuthAuthzResponse authzResponse;
        // TODO: using javax.servlet.http.HttpServletRequest instead of my HttpServletRequest
        try {
            authzResponse = OAuthAuthzResponse.oauthCodeAuthzResponse(new HttpServletRequestJavax(request));
            return authzResponse.getCode();
        } catch (OAuthProblemException e) {
            //response
            //throw new ApplicationAuthenticatorException("Exception while reading authorization code.", e);
            return null;
        }
    }

    public static String getAccessToken(String code) {
        OAuthClientRequest.TokenRequestBuilder builder = OAuthClientRequest
                .tokenLocation(Auth2ClientAccount.QUIZLET_ACCESS_TOKEN_URL)
                .setGrantType(GrantType.AUTHORIZATION_CODE)
                .setClientId(Auth2ClientAccount.CLIENT_ID)
                .setClientSecret(Auth2ClientAccount.CLIENT_SECRET)
                .setCode(code)
                .setRedirectURI(Auth2ClientAccount.REDIRECT_URI);
        OAuthClientRequest tokenRequest;
        try {
            tokenRequest = builder.buildBodyMessage();
        } catch (OAuthSystemException e) {
            e.printStackTrace();
            //response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return null;
        }

        OAuthClient oAuthClient = new OAuthClient(new URLConnectionClient());
        OAuthJSONAccessTokenResponse tokenResponse;
        try {
            tokenResponse = oAuthClient.accessToken(tokenRequest);
        } catch (OAuthSystemException e) {
            e.printStackTrace();
            //response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return null;
        } catch (OAuthProblemException e) {
            e.printStackTrace();
            //response.sendError(HttpServletResponse.SC_FORBIDDEN, e.getDescription());
            return null;
        }

        // the API sends back the username of the user in the access token
        //$username = $token['user_id']

        //System.out.println("token: [" + tokenResponse.getAccessToken() + "]  expiresIn:" + tokenResponse.getExpiresIn());
        //tokenResponse.getExpiresIn();
        return tokenResponse.getAccessToken();
    }

    public static void requestResorce() {

    }
}
