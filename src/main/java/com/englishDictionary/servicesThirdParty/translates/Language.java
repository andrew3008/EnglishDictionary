package com.englishDictionary.servicesThirdParty.translates;

public enum Language {
    RUSSIAN("ru"),
    ENGLISH("en"),
    PORTUGUESE("pt"),
    FRENCH("fr"), 
    GERMAN("gr"), 
    SPANISH("es");
    
    String value;
    
    private Language(String value) {
        this.value = value;
    }
    
    public String value(){
        return this.value;
    }
}
