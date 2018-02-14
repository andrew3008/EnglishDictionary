package com.englishDictionary.config.localStation;

/**
 * Created by Andrew on 8/12/2017.
 */
public class ConfigHomeStation extends LocaleResourcesAbstract {

    @Override
    public String getResourcesRootDir() {
        return "D:\\EnglishDictionary_Resources";
    }

    @Override
    public String getWordsFilesDir() {
        return "C:\\EnglishVocabulary\\VocabularyFiles\\";
    }

}
