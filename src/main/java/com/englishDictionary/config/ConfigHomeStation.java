package com.englishDictionary.config;

/**
 * Created by Andrew on 8/12/2017.
 */
public class ConfigHomeStation implements ConfigLocation {

    private static final String ROOT_DIR = "D:\\EnglishDictionary_Resources";
    private static final String WORDS_FILES_DIR = "C:\\EnglishVocabulary\\VocabularyFiles\\";
    private static final String FILE_NAME_OF_WORDS_FROM_EXCEL = "F:\\WordsFromExcel.json";

    public String getRootDir() {
        return ROOT_DIR;
    }

    public String getWordsFilesFolder() {
        return WORDS_FILES_DIR;
    }

    public String getFileNameOfWordsFromExcel() {
        return FILE_NAME_OF_WORDS_FROM_EXCEL;
    }

}