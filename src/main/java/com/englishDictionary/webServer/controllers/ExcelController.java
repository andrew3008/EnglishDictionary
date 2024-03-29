package com.englishDictionary.webServer.controllers;

import com.englishDictionary.servicesThirdParty.excel.BufferListOfWordsFromExcel;
import com.englishDictionary.servicesThirdParty.translates.Google2Translate;
import com.englishDictionary.servicesThirdParty.translates.Language;
import com.englishDictionary.servicesThirdParty.translates.YandexCloudTranslate;
import com.englishDictionary.webServer.HttpServletRequest;
import com.englishDictionary.webServer.HttpServletResponse;
import com.englishDictionary.webServer.annotations.Controller;
import com.englishDictionary.webServer.annotations.RequestMapping;
import com.englishDictionary.webServer.annotations.RequestMethod;
import com.englishDictionary.webServer.utils.SEDHttpClient;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.map.ObjectMapper;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

@Controller(url = "/excel/")
public class ExcelController {

    private static final String GOOGLE_TRANSLATE_URL_TEMPLATE = "http://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=ru&dt=t&q=%s&ie=UTF-8&oe=UTF-8";
    private static final String LINGVO_LIVE_GET_WORD_TRANSLATE_URL_TEMPLATE = "http://api.lingvolive.com/api/Translation/Minicard?text=%s&srclang=1033&dstlang=1049&returnXml=false";

    private SEDHttpClient sedHttpClient;

    public ExcelController() {
        sedHttpClient = new SEDHttpClient();
    }

    private static final Map<String, String> LINGVO_LIVE_GET_WORD_HEADERS = new HashMap<>();

    static {
        LINGVO_LIVE_GET_WORD_HEADERS.put("Content-Type", "application/x-www-form-urlencoded");
        LINGVO_LIVE_GET_WORD_HEADERS.put("Host", "api.lingvolive.com");
        LINGVO_LIVE_GET_WORD_HEADERS.put("Connection", "keep-alive");
        LINGVO_LIVE_GET_WORD_HEADERS.put("Upgrade-Insecure-Requests", "1");
        LINGVO_LIVE_GET_WORD_HEADERS.put("Accept", "application/json");
        LINGVO_LIVE_GET_WORD_HEADERS.put("Accept-Charset", "windows-1251,utf-8;q=0.7,*;q=0.7");
        LINGVO_LIVE_GET_WORD_HEADERS.put("Accept-Language", "ru-RU,ru;q=0.8,en-US;q=0.6,en;q=0.4");
        LINGVO_LIVE_GET_WORD_HEADERS.put("User-Agent", "Mozilla/5.0");
    }

    @RequestMapping(url = "getTranslateOfWord")
    public void getTranslateOfWord(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
//        String word = httpRequest.getParameter("word").trim();
//        try {
//            String trPhrase = translate.getCachedTranslation(word);
//            if (trPhrase == null) {
//                try {
//                    trPhrase = translate.getTranslation(Language.ENGLISH, Language.RUSSIAN, word);
//                } catch (Exception e) {
//                    e.printStackTrace();
//                }
//            }
//
//            httpResponse.getOutputStream().write(trPhrase);
//            httpResponse.setStatus(HttpResponseStatus.OK);
//        } catch (IOException e) {
//            e.printStackTrace();
//            httpResponse.setStatus(HttpResponseStatus.INTERNAL_SERVER_ERROR);
//        }

        String word = httpRequest.getParameter("word").trim();

        SEDHttpClient.HttpRequestResponse getHttpResponse = sedHttpClient.sendGetRequest(String.format(LINGVO_LIVE_GET_WORD_TRANSLATE_URL_TEMPLATE, word), LINGVO_LIVE_GET_WORD_HEADERS);
        if (getHttpResponse == null) {
            httpResponse.setStatus(HttpResponseStatus.INTERNAL_SERVER_ERROR);
            return;
        }

        ObjectMapper mapper = new ObjectMapper();
        JsonNode rootNode;
        try {
            rootNode = mapper.readTree(getHttpResponse.getContent());
        } catch (IOException e) {
            e.printStackTrace();
            httpResponse.setStatus(HttpResponseStatus.INTERNAL_SERVER_ERROR);
            return;
        }

        String translation = null;
        Iterator<String> fieldNames = rootNode.getFieldNames();
        while (fieldNames.hasNext()) {
            String fieldName = fieldNames.next();
            if ("isEmpty".equals(fieldName)) {
                if (rootNode.get(fieldName).asBoolean() == true) {
                    translation = "";
                    httpResponse.setStatus(HttpResponseStatus.NO_CONTENT);
                    return;
                }
            }

            if ("translation".equals(fieldName)) {
                Iterator<Map.Entry<String, JsonNode>> translationFields = rootNode.get(fieldName).getFields();
                while (translationFields.hasNext()) {
                    Map.Entry<String, JsonNode> field = translationFields.next();
                    if ("lingvoTranslations".equals(field.getKey())) {
                        translation = field.getValue().asText();
                        break;
                    }
                }
            }
        }

        try {
            httpResponse.getOutputStream().write(translation);
            httpResponse.setStatus(HttpResponseStatus.OK);
        } catch (IOException e) {
            e.printStackTrace();
            httpResponse.setStatus(HttpResponseStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private final YandexCloudTranslate translate = new YandexCloudTranslate();
//    private final Google2Translate translate = new Google2Translate();

    @RequestMapping(url = "getTranslateOfPhrase")
    public void getTranslateOfPhrase(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        String phrase = httpRequest.getParameter("phrase").trim();
        try {
            String trPhrase = translate.getCachedTranslation(phrase);
            if (trPhrase == null) {
                try {
                    trPhrase = translate.getTranslation(Language.ENGLISH, Language.RUSSIAN, phrase);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            httpResponse.getOutputStream().write(trPhrase);
            httpResponse.setStatus(HttpResponseStatus.OK);
        } catch (IOException e) {
            e.printStackTrace();
            httpResponse.setStatus(HttpResponseStatus.INTERNAL_SERVER_ERROR);
        }
    }

//    @RequestMapping(url = "getTranslateOfPhrase")
//    public void getTranslateOfPhrase(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
//        String phrase = httpRequest.getParameter("phrase").trim().replace(" ", "+");
//
//        URL url;
//        try {
//            url = new URL(String.format(GOOGLE_TRANSLATE_URL_TEMPLATE, phrase));
//        } catch (MalformedURLException e) {
//            e.printStackTrace();
//            httpResponse.setStatus(HttpResponseStatus.INTERNAL_SERVER_ERROR);
//            return;
//        }
//
//        try {
//            URLConnection uc = url.openConnection();
//            uc.setRequestProperty("user-agent", "Mozilla/5.0");
//            uc.setRequestProperty("cookie", "GOOGLE_ABUSE_EXEMPTION=ID=6fc09adeedeeb6c3:TM=1537612894:C=r:IP=91.219.56.103-:S=APGng0tZlvce1ABBOLPFoNDWGx-umRvLjA");
//
//            String inputLine;
//            String translateResponce = "";
//            BufferedReader in = new BufferedReader(new InputStreamReader(uc.getInputStream(), Config.CHARSET));
//            while ((inputLine = in.readLine()) != null) {
//                translateResponce += inputLine;
//            }
//            httpResponse.getOutputStream().write(translateResponce.substring(4, translateResponce.indexOf("\",\"")));
//            httpResponse.setStatus(HttpResponseStatus.OK);
//            in.close();
//        } catch (IOException e) {
//            e.printStackTrace();
//            httpResponse.setStatus(HttpResponseStatus.INTERNAL_SERVER_ERROR);
//            return;
//        }
//    }

    @RequestMapping(url = "exportListOfWords", method = RequestMethod.POST)
    public void exportListOfWords(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        BufferListOfWordsFromExcel.INSTANCE.updateBuffer(httpRequest.getContent());
    }
}
