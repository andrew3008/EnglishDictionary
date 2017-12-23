package com.englishDictionary.servicesThirdParty.lingualeo;

import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.map.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.englishDictionary.servicesThirdParty.lingualeo.LinguaLeoConfig.DELETE_WORDS_FROM_DICTIONARY;
import static com.englishDictionary.servicesThirdParty.lingualeo.LinguaLeoConfig.EMAIL;
import static com.englishDictionary.servicesThirdParty.lingualeo.LinguaLeoConfig.EXPORT_WORDS_URL;
import static com.englishDictionary.servicesThirdParty.lingualeo.LinguaLeoConfig.EXPORT_WORD_URL;
import static com.englishDictionary.servicesThirdParty.lingualeo.LinguaLeoConfig.GET_DICTIONARIES;
import static com.englishDictionary.servicesThirdParty.lingualeo.LinguaLeoConfig.GET_WORDS_FROM_DICTIONARY;
import static com.englishDictionary.servicesThirdParty.lingualeo.LinguaLeoConfig.IMPORT_URL;
import static com.englishDictionary.servicesThirdParty.lingualeo.LinguaLeoConfig.LOGIN_URL;
import static com.englishDictionary.servicesThirdParty.lingualeo.LinguaLeoConfig.PASSWORD;

/**
 * Created by Andrew on 8/20/2016.
 */
public class LinguaLeoClient {

    private LinguaLeoHttpClient httpClient;

    public LinguaLeoClient() {
        httpClient = new LinguaLeoHttpClient();
    }


    /********************************************************************************************************/
    /*                                                  Authorization                                       */
    /********************************************************************************************************/
    public boolean authorization() {
        Boolean isAuthorized = isAuthorized();
        if (Boolean.TRUE.equals(isAuthorized)) {
            isAuthorized = true;
        } else if (Boolean.FALSE.equals(isAuthorized)) {
            isAuthorized = Boolean.TRUE.equals(login()) ? true : false;
        }

        if (isAuthorized) {
            httpClient.buildSecurityCooker();
        }

        return isAuthorized;
    }

    private Boolean isAuthorized() {
        String response = httpClient.sendGetRequest(LinguaLeoConfig.CHECK_AUTHORIZED_URL);
        //System.out.println("[LinguaLeo] [isAuthorized] response:" + response);

        // Handling response
        ObjectMapper mapper = new ObjectMapper();
        JsonNode rootNode;
        try {
            rootNode = mapper.readTree(response);
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }

        Iterator<String> fieldNames = rootNode.getFieldNames();
        while (fieldNames.hasNext()) {
            // TODO: handling "error_msg" to client
            String fieldName = fieldNames.next();
            if (fieldName.equals("is_authorized")) {
                return rootNode.get("is_authorized").asBoolean() ? Boolean.TRUE : Boolean.FALSE;
            }
            //[{"error_msg":"","is_authorized":false}
        }

        return null;
    }

    private Boolean login() {
        String resourceURLRes = LOGIN_URL + "?email=" + EMAIL + "&password=" + PASSWORD;
        httpClient.sendGetRequest(resourceURLRes);
        return Boolean.TRUE;

        /*
        // TODO: Handle {"error_msg":"Email or password is incorrect","error_code":403}

        // Handling response
        ObjectMapper mapper = new ObjectMapper();
        JsonNode rootNode;
        try {
            rootNode = mapper.readTree(response);
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }

        boolean loginSuccess = true;
        Iterator<String> fieldNames = rootNode.getFieldNames();
        while (fieldNames.hasNext()) {
            String fieldName = fieldNames.next();
            if (fieldName.equals("error_code")) {
                loginSuccess = false;
                break;
            }
        }

        //{"error_msg":"","user":{"lang_interface":"ru","lang_native":"ru","user_id":12800976,"nickname":"andrew3007","leo_pic_url":"http:\/\/staticcdn.lingualeo.com\/8ac94ea62\/images\/tasks-leo-2.png","avatar":"https:\/\/contentcdn.lingualeo.com\/uploads\/avatar\/0.png","avatar_mini":"https:\/\/contentcdn.lingualeo.com\/uploads\/avatar\/0s100.png","fullname":"andrew3007","fname":"","sname":"","age":33,"address":"Russian Federation, Nizhegorod, Nizhniy Novgorod","sex":1,"birth":"1983-03-01","langlevel":3,"daily_hours":1,"create_at":"2015-10-21 13:11:26","is_gold":true,"premium_type":1,"premium_until":"2017-10-23T13:24:24+0000","xp_level":15,"xp_title":"Быстроногий охотник","xp_points":5824,"xp_min_points":5200,"xp_max_points":6300,"hungry_pct":0,"hungry_points":0,"hungry_max_points":150,"meatballs":320,"words_cnt":40,"words_known":415,"denied_services":[],"autologin_key":"ff3dbf3083ac622c666c1ee854227625","port_version":"1.6.1","refcode":"9cln92"},"need_fields":[],"daily_bonus":0,"rev":201}
        // Build seccurity cooker
        // "Cookie", "lingualeouid=1471709174511533; lang=ru; browser-plugins-msg-hide=1; userid=12800976; servid=5c01000a4a5322a2993c0eb987f2d0983c3e43e160eb3bb6a877055e10ca9a73dd0b5e8b8e6ccd17; remember=d053c300c0b065e8a998146fa9a59d296046b7999f136f0868d5ec7df77d46ca7f8dc55809a958fe"
        if (loginSuccess) {
            LinguaLeoApiSecurityCooker securityCooker = new LinguaLeoApiSecurityCooker();
            //"user_id" from response
            //securityCooker.setUserId();
        }

        return loginSuccess ? Boolean.TRUE : Boolean.FALSE;*/
    }


    /********************************************************************************************************/
    /*                                                  Sets of words                                       */
    /********************************************************************************************************/
    public List<String> getExistSets() {
        String responce = httpClient.sendGetRequest(GET_DICTIONARIES);
        return Collections.EMPTY_LIST;
    }

    public List<LinguaLeoWordCard> getWordsFromDictionary() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("sortBy", "date");
        params.put("wordType", "0");
        params.put("page", "1");
        params.put("filter", "all");
        params.put("groupId", "dictionary");
        String response = httpClient.sendPostRequest(GET_WORDS_FROM_DICTIONARY, params);

        ObjectMapper mapper = new ObjectMapper();
        JsonNode rootNode;
        try {
            rootNode = mapper.readTree(response);
        } catch (IOException e) {
            e.printStackTrace();
            return Collections.emptyList();
        }

        List<LinguaLeoWordCard> linguaLeoWordCards = new ArrayList<>();
        JsonNode userDictNode = rootNode.get("userdict3");
        if (userDictNode != null && userDictNode.getElements().hasNext()) {
            JsonNode userDictItemNode = userDictNode.getElements().next();
            Iterator<String> userDictItemFieldNames = userDictItemNode.getFieldNames();
            boolean isExistArrayWords = false;
            while (userDictItemFieldNames.hasNext()) {
                if (userDictItemFieldNames.next().equals("words")) {
                    isExistArrayWords = true;
                    break;
                }
            }
            if (!isExistArrayWords) {
                return Collections.EMPTY_LIST;
            }

            JsonNode wordsNode = userDictNode.getElements().next().get("words");
            if (wordsNode != null) {
                Iterator<JsonNode> wordNodes = wordsNode.getElements();
                while (wordNodes.hasNext()) {
                    JsonNode wordNode = wordNodes.next();
                    LinguaLeoWordCard linguaLeoWordCard = new LinguaLeoWordCard();
                    linguaLeoWordCards.add(linguaLeoWordCard);

                    if (wordNode.get("word_value") != null) {
                        linguaLeoWordCard.setWord(wordNode.get("word_value").asText());
                    }

                    if (wordNode.get("picture_url") != null) {
                        linguaLeoWordCard.setCurrentPictureUrl(wordNode.get("picture_url").asText());
                    }

                    if (wordNode.get("pictures") != null) {
                        JsonNode additionalPicturesNode = wordNode.get("pictures");
                        Iterator<String> filedNames = wordNode.get("pictures").getFieldNames();
                        List<String> alternativePictureUrls = new ArrayList<>();
                        while (filedNames.hasNext()) {
                            String fieldName = filedNames.next();
                            String pictUrl = additionalPicturesNode.get(fieldName).asText();
                            alternativePictureUrls.add(pictUrl);
                        }
                        linguaLeoWordCard.setAlternativePictureUrls(alternativePictureUrls);
                    }
                }
            }
        }

        return linguaLeoWordCards;
    }


    /********************************************************************************************************/
    /*                                                List of words for set                                 */
    /********************************************************************************************************/
    public void addWord(String word, String translate) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("word", word);
        params.put("tword", translate);
        httpClient.sendPostRequest(EXPORT_WORD_URL, params);
    }

    public void addWords(List<LinguaLeoWordInfo> wordInfos) {
        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 0; i < wordInfos.size(); ++i) {
            params.put("words[" + i + "][word]", wordInfos.get(i).getWord());
            params.put("words[" + i + "][tword]", wordInfos.get(i).getWordTranslate());
        }
        httpClient.sendPostRequest(EXPORT_WORDS_URL, params);
    }

    public void getWordInfo() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("word", "award"); //a.replace(/&/g, "%26")
        params.put("include_media", "1");
        params.put("add_word_forms", "1");
        httpClient.sendPostRequest(IMPORT_URL, params);
    }

    public void deleteAllWords() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("all", "1");
        params.put("groupId", "dictionary");
        params.put("filter", "all");
        params.put("search", "");
        params.put("wordType", "0");
        params.put("delete_source", "dictionary_toolbar");
        httpClient.sendPostRequest(DELETE_WORDS_FROM_DICTIONARY, params);
    }
}
