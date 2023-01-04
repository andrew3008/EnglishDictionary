package com.englishDictionary.servicesThirdParty.translates;

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
}
