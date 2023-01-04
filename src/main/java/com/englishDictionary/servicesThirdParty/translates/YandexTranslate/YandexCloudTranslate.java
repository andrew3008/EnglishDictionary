package com.englishDictionary.servicesThirdParty.translates.YandexTranslate;

import com.englishDictionary.servicesThirdParty.translates.BaseTranslate;
import com.englishDictionary.servicesThirdParty.translates.Language;
import com.englishDictionary.servicesThirdParty.translates.YandexTranslate.http.HttpConnectionUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

// http://omegat.org/
/**
 * @see <a href="https://cloud.yandex.com/docs/translate/api-ref/Translation/">Translation API</a>
 * https://cloud.yandex.com/en-ru/docs/iam/concepts/authorization/oauth-token
 * The validity period of an OAuth token is one year. Then you must get new OAuth token and repeat the authentication process.
 */
public class YandexCloudTranslate extends BaseTranslate {

    private static final String OAUTH_TOKEN = "y0_AgAAAABnjTu-AATuwQAAAADYer8G3EjcpllgQpKTekLC5rzBgfplP9c";
    private static final String FOLDER_ID = "b1g7jlooh939n540fm0k";

    private static final int MAX_TEXT_LENGTH = 10000;
    private static final int IAM_TOKEN_TTL_SECONDS = 3600; // Recommended value

    private static final String IAM_TOKEN_URL = "https://iam.api.cloud.yandex.net/iam/v1/tokens";
    private static final String TRANSLATE_URL = "https://translate.api.cloud.yandex.net/translate/v2/translate";

    private String IAMErrorMessage = null;
    private String cachedIAMToken = null;
    private long lastIAMTokenTime = 0L;

    @Override
    protected String translate(final Language sLang, final Language tLang, final String text) throws Exception {
        String trText = text.length() > MAX_TEXT_LENGTH ? text.substring(0, MAX_TEXT_LENGTH - 3) + "..." : text;
        String prev = getFromCache(trText);
        if (prev != null) {
            return prev;
        }

        String IAMToken = getIAMToken(OAUTH_TOKEN);
        if (IAMToken == null) {
            throw new Exception(IAMErrorMessage);
        }

        String request = createJsonRequest(sLang, tLang, trText, FOLDER_ID);

        Map<String, String> headers = new TreeMap<>();
        headers.put("Authorization", "Bearer " + IAMToken);

        String response;
        try {
            response = HttpConnectionUtils.postJSON(TRANSLATE_URL, request, headers);
        } catch (HttpConnectionUtils.ResponseError e) {
            String errorMessage = extractErrorMessage(e.body);
            if (errorMessage == null) {
                errorMessage = "YANDEX_CLOUD_BAD_TRANSLATE_RESPONSE";
                throw new Exception(errorMessage);
            }
            throw e;
        }
        if (response == null) {
            return null;
        }
        String tr = extractTranslation(response);
        if (tr == null) {
            return null;
        }
        //tr = cleanSpacesAroundTags(tr, trText);
        putToCache(trText, tr);
        return tr;
    }

    private String extractErrorMessage(final String json) {
        JsonNode rootNode;
        ObjectMapper mapper = new ObjectMapper();
        try {
            rootNode = mapper.readTree(json);
            return rootNode.get("message").asText();
        } catch (Exception ex) {
            System.out.println("YANDEX_CLOUD_BAD_ERROR_REPORT ex:" + ex.toString());
            return null;
        }
    }

    protected String createJsonRequest(final Language sLang, final Language tLang, final String trText,
                                       final String folderId) throws JsonProcessingException {
        Map<String, Object> params = new TreeMap<>();
        params.put("sourceLanguageCode", sLang.value());
        params.put("targetLanguageCode", tLang.value());
        params.put("folderId", folderId);
        params.put("format", "PLAIN_TEXT"); // use "HTML" for keeping tags
        params.put("texts", Collections.singletonList(trText));
        return new ObjectMapper().writeValueAsString(params);
    }

    protected String extractTranslation(final String json) {
        JsonNode rootNode;
        ObjectMapper mapper = new ObjectMapper();
        try {
            rootNode = mapper.readTree(json);
            return rootNode.get("translations").get(0).get("text").asText();
        } catch (Exception ex) {
            System.out.println("JSON_ERROR ex:" + ex.toString());
            return "YANDEX_CLOUD_BAD_TRANSLATE_RESPONSE";
        }
    }

    private String getIAMToken(final String oAuthToken) {
        if (System.currentTimeMillis() - lastIAMTokenTime > IAM_TOKEN_TTL_SECONDS * 1000) {
            String request = "{\"yandexPassportOauthToken\":\"" + oAuthToken + "\"}";
            String response;
            JsonNode rootNode;
            ObjectMapper mapper = new ObjectMapper();

            try {
                response = HttpConnectionUtils.postJSON(IAM_TOKEN_URL, request, null);
            } catch (HttpConnectionUtils.ResponseError e) {
                // Try to extract error message from the error body
                IAMErrorMessage = extractErrorMessage(e.body);
                if (IAMErrorMessage == null) {
                    IAMErrorMessage = "YANDEX_CLOUD_BAD_IAM_RESPONSE";
                }
                return null;
            } catch (IOException e) {
                IAMErrorMessage = e.getLocalizedMessage();
                return null;
            }

            try {
                rootNode = mapper.readTree(response);
                cachedIAMToken = rootNode.get("iamToken").asText();
                lastIAMTokenTime = System.currentTimeMillis();
            } catch (Exception ex) {
                System.out.println("YANDEX_CLOUD_BAD_IAM_RESPONSE ex:" + ex.toString());
                IAMErrorMessage = "YANDEX_CLOUD_BAD_IAM_RESPONSE";
                return null;
            }
        }
        return cachedIAMToken;
    }
}
