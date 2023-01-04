package com.englishDictionary.servicesThirdParty.translates;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.englishDictionary.servicesThirdParty.translates.http.HttpConnectionUtils;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Support of Google Translate API v.2 machine translation.
 * @see <a href="https://cloud.google.com/translate/docs/basic/setup-basic">Translation API</a>
 */
public class Google2Translate extends BaseTranslate {

    protected static final String API_KEY = ""; //"google.api.key";
    protected static final String PREMIUM_KEY = ""; //"google.api.premium";
    protected static final String GT_URL = "https://translation.googleapis.com/language/translate/v2";
    private static final int MAX_TEXT_LENGTH = 5000;

    /**
     * Query google translate API and return translation text.
     * @param sLang source language.
     * @param tLang target language.
     * @param text source text.
     * @return translation.
     * @throws Exception when error occurred.
     */
    @Override
    protected String translate(Language sLang, Language tLang, String text) throws Exception {
        String trText = text.length() > MAX_TEXT_LENGTH ? text.substring(0, MAX_TEXT_LENGTH - 3) + "..." : text;

        String prev = getFromCache(trText);
        if (prev != null) {
            return prev;
        }

        String targetLang = tLang.value();

        Map<String, String> params = new TreeMap<String, String>();
        if (isPremium()) {
            params.put("model", "nmt");
        }
        params.put("key", API_KEY);
        params.put("source", sLang.value());
        params.put("target", targetLang);
        params.put("q", trText);
        // The 'text' format mangles the tags, whereas the 'html' encodes some characters
        // as entities. Since it's more reliable to convert the entities back, we are
        // using 'html' and convert the text with the unescapeHTML() method.
        params.put("format", "html");

        Map<String, String> headers = new TreeMap<String, String>();
        headers.put("X-HTTP-Method-Override", "GET");

        String v = HttpConnectionUtils.post(GT_URL, params, headers);
        String tr = getJsonResults(v);
        if (tr == null) {
            return null;
        }
        tr = unescapeHTML(tr);
        //tr = cleanSpacesAroundTags(tr, trText);
        putToCache(trText, tr);
        return tr;
    }

    /**
     * Parse response and return translation.
     * @param json response string.
     * @return translation text.
     */
    protected String getJsonResults(String json) {
        ObjectMapper mapper = new ObjectMapper();
        try {
            Response response = mapper.readValue(json, Response.class);
            List<Translation> translations = response.getData().getTranslations();
            if (translations.size() > 0) {
                return translations.get(0).getTranslatedText();
            }
        } catch (Exception e) {
            System.out.println("MT_JSON_ERROR e:" + e.toString());
            return "MT_JSON_ERROR";
        }
        return null;
    }

    /**
     * Whether or not to use the new Neural Machine Translation System
     *
     * @see <a href="https://research.googleblog.com/2016/09/a-neural-network-for-machine.html">A Neural
     *      Network for Machine Translation, at Production Scale</a>
     */
    private boolean isPremium() {
        return PREMIUM_KEY.isEmpty();
    }

    /**
     * Data schema class for Google2 translate API response.
     */
    public static final class Response {
        private Data data;

        public Data getData() {
            return data;
        }

        public void setData(Data data) {
            this.data = data;
        }

        @Override
        public String toString() {
            return "Response{" + "data=" + data + '}';
        }
    }

    /**
     * Data schema class.
     */
    public static final class Data {
        private List<Translation> translations;

        public List<Translation> getTranslations() {
            return translations;
        }

        public void setTranslations(List<Translation> translations) {
            this.translations = translations;
        }

        @Override
        public String toString() {
            return "Data{" + "translations=" + translations + '}';
        }
    }

    /**
     * Data schema class.
     */
    public static final class Translation {
        private String translatedText;
        private String detectedSourceLanguage;

        public String getTranslatedText() {
            return translatedText;
        }

        public void setTranslatedText(String translatedText) {
            this.translatedText = translatedText;
        }

        public String getDetectedSourceLanguage() {
            return detectedSourceLanguage;
        }

        public void setDetectedSourceLanguage(String detectedSourceLanguage) {
            this.detectedSourceLanguage = detectedSourceLanguage;
        }

        @Override
        public String toString() {
            return "Translation{translatedText='" + translatedText + "', detectedSourceLanguage='"
                    + detectedSourceLanguage + "'}";
        }
    }
}
