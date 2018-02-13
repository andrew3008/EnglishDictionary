package com.englishDictionary.config;

import com.englishDictionary.config.localStation.ConfigHomeStation;
import com.englishDictionary.config.localStation.ConfigWorkStation;
import com.englishDictionary.config.openShiftClaster.ConfigOpenShiftCluster;

import java.nio.charset.StandardCharsets;

public class Config implements EnvironmentResourcesInterface {

    public static final Config INSTANCE = new Config();

    public static final int WEB_SERVER_PORT = 8080;
    public static final String FORVO_API_KEY = "9abf6bd699950a762f5793dce5a32a56";
    public static final String YANDEX_WEBDAV_AUTHORIZATION_TOKEN = "AQAEA7qgySSkAAS9YffJNgqU1k9qp75Zd9Dq4WY";
    public static final String WORDS_FILES_CONTENT_FILE = "_content.json";
    public static final String FILE_NAME_OF_WORDS_FROM_EXCEL_ALIAS = "WordsFromExcel";
    public static final String CHARSET = StandardCharsets.UTF_8.name();

    private EnvironmentResourcesInterface environmentResources;
    private EnvironmentType environmentType;

    public Config() {
        String environmentTypeSV = System.getenv(EnvironmentVariables.SED_ENVIRONMENT_TYPE.name());
        if (EnvironmentType.HOME_STATION.name().equals(environmentTypeSV)) {
            environmentResources = new ConfigHomeStation();
            environmentType = EnvironmentType.HOME_STATION;
        } else if (EnvironmentType.WORK_STATION.name().equals(environmentTypeSV)) {
            environmentResources = new ConfigWorkStation();
            environmentType = EnvironmentType.WORK_STATION;
        } else if (EnvironmentType.OPEN_SHIFT_CLUSTER.name().equals(environmentTypeSV)) {
            environmentResources = new ConfigOpenShiftCluster();
            environmentType = EnvironmentType.OPEN_SHIFT_CLUSTER;
        }
    }

    public EnvironmentType getEnvironmentType() {
        return environmentType;
    }

    public boolean isNeedExportWordsFromExcelThroughBuffer() {     
         return (environmentType == EnvironmentType.OPEN_SHIFT_CLUSTER) ? false : (Boolean.TRUE.toString().compareToIgnoreCase(System.getenv(EnvironmentVariables.SED_NEED_EXPORT_WORDS_FROM_EXCEL_TROUGH_BUFFER.name())) == 0);
    }

    public String getFileNameOfWordsFromExcel() {
        return System.getenv(EnvironmentVariables.SED_FILE_PATH_WORDS_FROM_EXCEL.name());
    }

    
    /*********************************************************************************************************************************************/
    /*                                                     Delegating to a config of the adopted environment                                     */                                                  
    /*********************************************************************************************************************************************/
    @Override
    public String getResourcesRootDir() {
        return environmentResources.getResourcesRootDir();
    }

    @Override
    public String getCommonImagesDir() {
        return environmentResources.getCommonImagesDir();
    }

    @Override
    public String getWordCardsImagesDir() {
        return environmentResources.getWordCardsImagesDir();
    }

    @Override
    public String getDictionariesDir() {
        return environmentResources.getDictionariesDir();
    }

    @Override
    public String getDigitalDictionariesDir() {
        return environmentResources.getDigitalDictionariesDir();
    }

    @Override
    public String getLingvoUniversalDir() {
        return environmentResources.getLingvoUniversalDir();
    }

    @Override
    public String getLingvoUniversalSoundDatFilePath() {
        return environmentResources.getLingvoUniversalSoundDatFilePath();
    }

    @Override
    public String getLingvoUniversalSoundIndFilePath() {
        return environmentResources.getLingvoUniversalSoundIndFilePath();
    }

    @Override
    public String getMED2Dir() {
        return environmentResources.getMED2Dir();
    }

    @Override
    public String getMED2WordCardHeadersFilePath() {
        return environmentResources.getMED2WordCardHeadersFilePath();
    }

    @Override
    public String getOALD9Dir() {
        return environmentResources.getOALD9Dir();
    }

    @Override
    public String getOALD9ImagesDir() {
        return environmentResources.getOALD9ImagesDir();
    }

    @Override
    public String getOALD9SoundDatFilePath() {
        return environmentResources.getOALD9SoundDatFilePath();
    }

    @Override
    public String getOALD9SoundIndFilePath() {
        return environmentResources.getOALD9SoundIndFilePath();
    }

    @Override
    public String getOALD9TranscriptionsFilePath() {
        return environmentResources.getOALD9TranscriptionsFilePath();
    }

    @Override
    public String getLDOCE6Dir() {
        return environmentResources.getLDOCE6Dir();
    }

    @Override
    public String getLDOCE6ImagesDir() {
        return environmentResources.getLDOCE6ImagesDir();
    }

    @Override
    public String getLDOCE6SoundDatFilePath() {
        return environmentResources.getLDOCE6SoundDatFilePath();
    }

    @Override
    public String getLDOCE6SoundIndFileParh() {
        return environmentResources.getLDOCE6SoundIndFileParh();
    }

    @Override
    public String getIrregularVerbsFilePath() {
        return environmentResources.getIrregularVerbsFilePath();
    }

    @Override
    public String getMnemonicsDir() {
        return environmentResources.getMnemonicsDir();
    }

    @Override
    public String getMnemonicsFlagsDir() {
        return environmentResources.getMnemonicsFlagsDir();
    }

    @Override
    public String getMnemonicsImagesDir() {
        return environmentResources.getMnemonicsImagesDir();
    }

    @Override
    public String getMnemonicsFilePath() {
        return environmentResources.getMnemonicsFilePath();
    }

    @Override
    public String getForvoDir() {
        return environmentResources.getForvoDir();
    }

    @Override
    public String getWordsFilesDir() {
        return environmentResources.getWordsFilesDir();
    }

}
