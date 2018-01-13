package com.englishDictionary.config;

import com.englishDictionary.config.localStation.ConfigHomeStation;
import com.englishDictionary.config.localStation.ConfigWorkStation;
import com.englishDictionary.config.openShiftClaster.ConfigOpenShiftCluster;
import httl.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
        String environmentTypeSV = System.getenv("SED_ENVIRONMENT_TYPE");
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
         return (environmentType == EnvironmentType.OPEN_SHIFT_CLUSTER) ? false : (Boolean.TRUE.toString().compareToIgnoreCase(System.getenv("SED_NEED_EXPORT_WORDS_FROM_EXCEL_TROUGH_BUFFER")) == 0);
    }

    private static final Set<String> WORD_UNNECESSARY_FOR_HANDLING = Stream.of(
            // Articles
            "a",
            "an",
            "the",

            // To be
            "am",
            "is",
            "are",
            "was",
            "were",
            "be",
            "been",

            // Pronouns
            "i",
            "you",
            "he",
            "she",
            "it",
            "we",
            "they",
            "me",
            "him",
            "her",
            "it",
            "us",
            "them",
            "my",
            "your",
            "his",
            "her",
            "its",
            "our",
            "their",
            "mine",
            "yours",
            "his",
            "hers",
            "ours",
            "theirs",
            "this",
            "that",
            "these",
            "those",
            "such",
            "myself",
            "yourself",
            "himself",
            "herself",
            "itself",
            "ourselves",
            "yourselves",
            "themselves",
            "who",
            "what",
            "which",
            "whose",
            "whoever",
            "whatever",
            "whichever",
            "some",
            "something",
            "somebody",
            "someone",
            "any",
            "anything",
            "anybody",
            "anyone",
            "no",
            "nothing",
            "nobody",
            "none",
            "neither",
            "other",
            "another",
            "all",
            "each",
            "both",
            "either",
            "every",
            "everything",
            "everybody",
            "everyone",

            // Prepositions
            "at",
            "on",
            "in",
            "about",
            "above",
            "below",
            "after",
            "before",
            "by",
            "for",
            "from",
            "of",
            "since",
            "to",
            "with",
            "among",
            "between",

            // Particles
            "alone",
            "but",
            "even",
            "just",
            "merely",
            "only",
            "solely",
            "all",
            "even",
            "just",
            "never",
            "simply",
            "still",
            "yet",
            "exactly",
            "just",
            "precisely",
            "right",
            "up",
            "down",
            "not",
            "no",
            "nor",
            "else",

            // Modal verbs
            "can",
            "could",
            "may",
            "might",
            "must",
            "got",
            "be",
            "need",
            "ought",
            "should",
            "would",
            "shall",
            "dare",
            "used",
            "have",
            "has",
            "will",
            "wont",

            // Conjunctions
            "and",
            "or",
            "but",
            "as",
            "like",
            "far",
            "long",
            "soon",
            "because",
            "both",
            "if",
            "moreover",
            "neither",
            "now",
            "once",
            "still",
            "than",
            "thus",
            "what",
            "yet",
            "with",
            "without",
            "within",
            "according",
            "beyond",
            "whereas"
    ).collect(Collectors.toSet());

    public static boolean isNecessaryWord(String word) {
        return !(Config.WORD_UNNECESSARY_FOR_HANDLING.contains(word.toLowerCase()) || StringUtils.isNumber(word));
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

    @Override
    public String getFileNameOfWordsFromExcel() {
        return environmentResources.getFileNameOfWordsFromExcel();
    }

}
