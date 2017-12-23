package com.englishDictionary.servicesThirdParty.lingualeo;

/**
 * Created by Andrew on 8/20/2016.
 */
public class LinguaLeoWordInfo {
    private String word;
    private String wordTranslate;

    public LinguaLeoWordInfo(String word, String wordTranslate) {
        this.word = word;
        this.wordTranslate = wordTranslate;
    }

    public String getWord() {
        return word;
    }

    public String getWordTranslate() {
        return wordTranslate;
    }
}
