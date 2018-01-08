package com.englishDictionary.config.localStation;

/**
 * Created by Andrew on 8/12/2017.
 */
public class ConfigWorkStation implements ConfigLocation {

    private static final String ROOT_DIR = "C:\\English\\EnglishDictionary_Resources\\";
    private static final String WORDS_FILES_DIR = "C:\\English\\VocabularyFiles\\";
    private static final String FILE_NAME_OF_WORDS_FROM_EXCEL = "E:\\tmp\\WordsFromExcel.json";

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

