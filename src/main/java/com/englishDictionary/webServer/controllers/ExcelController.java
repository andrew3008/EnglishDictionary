package com.englishDictionary.webServer.controllers;

import com.englishDictionary.config.Config;
import com.englishDictionary.servicesThirdParty.excel.BufferListOfWordsFromExcel;
import com.englishDictionary.webServer.HttpServletRequest;
import com.englishDictionary.webServer.HttpServletResponse;
import com.englishDictionary.webServer.annotations.Controller;
import com.englishDictionary.webServer.annotations.RequestMapping;
import com.englishDictionary.webServer.annotations.RequestMethod;
import com.englishDictionary.webServer.utils.SEDHttpClient;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.map.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
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

    @RequestMapping(url = "getTranslateOfWord")
    public void getTranslateOfWord(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        String word = httpRequest.getParameter("word").trim();

        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/x-www-form-urlencoded");
        headers.put("Host", "api.lingvolive.com");
        headers.put("Connection", "keep-alive");
        headers.put("Upgrade-Insecure-Requests", "1");
        headers.put("Accept", "application/json");
        headers.put("Accept-Charset", "windows-1251,utf-8;q=0.7,*;q=0.7");
        headers.put("Accept-Language", "ru-RU,ru;q=0.8,en-US;q=0.6,en;q=0.4");
        headers.put("User-Agent", "Mozilla/5.0");
        SEDHttpClient.HttpRequestResponse getHttpResponse = sedHttpClient.sendGetRequest(String.format(LINGVO_LIVE_GET_WORD_TRANSLATE_URL_TEMPLATE, word), headers);
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

    @RequestMapping(url = "getTranslateOfPhrase")
    public void getTranslateOfPhrase(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        String phrase = httpRequest.getParameter("phrase").trim().replace(" ", "+");

        URL url;
        try {
            url = new URL(String.format(GOOGLE_TRANSLATE_URL_TEMPLATE, phrase));
        } catch (MalformedURLException e) {
            e.printStackTrace();
            httpResponse.setStatus(HttpResponseStatus.INTERNAL_SERVER_ERROR);
            return;
        }

        try {
            URLConnection uc = url.openConnection();
            uc.setRequestProperty("User-Agent", "Mozilla/5.0");

            String inputLine;
            String translateResponce = "";
            BufferedReader in = new BufferedReader(new InputStreamReader(uc.getInputStream(), StandardCharsets.UTF_8.name()));
            while ((inputLine = in.readLine()) != null) {
                translateResponce += inputLine;
            }
            httpResponse.getOutputStream().write(translateResponce.substring(4, translateResponce.indexOf("\",\"")));
            httpResponse.setStatus(HttpResponseStatus.OK);
            in.close();
        } catch (IOException e) {
            e.printStackTrace();
            httpResponse.setStatus(HttpResponseStatus.INTERNAL_SERVER_ERROR);
            return;
        }
    }

    @RequestMapping(url = "exportListOfWords", method = RequestMethod.POST)
    public void exportListOfWords(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        BufferListOfWordsFromExcel.INSTANCE.updateBuffer(httpRequest.getContent());
    }

}
