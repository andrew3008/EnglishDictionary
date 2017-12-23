package com.englishDictionary.servicesThirdParty.lingualeo;

import java.util.List;

/**
 * Created by Andrew on 11/7/2016.
 */
public class LinguaLeoWordCard {
    private String word;
    private String currentPictureUrl;
    private List<String> alternativePictureUrls;

    public String getWord() {
        return word;
    }

    public void setWord(String word) {
        this.word = word;
    }

    public String getCurrentPictureUrl() {
        return currentPictureUrl;
    }

    public void setCurrentPictureUrl(String currentPictureUrl) {
        this.currentPictureUrl = currentPictureUrl;
    }

    public List<String> getAlternativePictureUrls() {
        return alternativePictureUrls;
    }

    public void setAlternativePictureUrls(List<String> alternativePictureUrls) {
        this.alternativePictureUrls = alternativePictureUrls;
    }
}
