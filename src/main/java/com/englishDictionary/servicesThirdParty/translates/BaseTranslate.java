package com.englishDictionary.servicesThirdParty.translates;

import com.englishDictionary.servicesThirdParty.translates.http.HTMLUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public abstract class BaseTranslate {

    private final Map<String, String> cache = Collections.synchronizedMap(new HashMap<String, String>());

    public BaseTranslate() {
    }

    public String getTranslation(Language sLang, Language tLang, String text) throws Exception {
        return translate(sLang, tLang, text);
    }

    public String getCachedTranslation(String text) {
        return getFromCache(text);
    }

    protected abstract String translate(Language sLang, Language tLang, String text) throws Exception;

    protected String getFromCache(String text) {
        return cache.get(text);
    }

    protected String putToCache(String text, String result) {
        return cache.put(text, result);
    }

    protected void clearCache() {
        cache.clear();
    }

    /** Convert entities to character. Ex: "&#39;" to "'". */
    protected static String unescapeHTML(String text) {
        return HTMLUtils.entitiesToChars(text);
    }
}
