package com.englishDictionary.config.localStation;

/**
 * Created by Andrew on 8/12/2017.
 */
public class ConfigWorkStation extends LocaleResourcesAbstract {

    @Override
    public String getResourcesRootDir() {
        return "C:\\English\\EnglishDictionary_Resources\\";
    }

    @Override
    public String getWordsFilesDir() {
        return "C:\\English\\VocabularyFiles\\___GLOSSARIES_APP\\";
    }

}

