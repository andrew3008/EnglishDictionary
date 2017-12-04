package com.englishDictionary.config;

import httl.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

public class Config  {
    private static ConfigLocation configLocation = (Boolean.TRUE.toString().equals(System.getenv("IS_WORK_STATION")) ? new ConfigWork() : new ConfigHome());

    public static final int WEB_SERVER_PORT = 8080;

    public static final String FORVO_API_KEY = "9abf6bd699950a762f5793dce5a32a56";

    public static final String ROOT_DIR = configLocation.getRootDir();

    public static final String DICTIONARIES_FOLDER = ROOT_DIR + "\\Dictionaries\\";

    //public static final String LINGVO_SOUND_EN_FOLDER = ROOT_DIR + "\\Lingvo\\Sounds\\";
    //public static final String LINGVO_SOUND_FILE = LINGVO_SOUND_EN_FOLDER + "SoundEn.dat";

    public static final String MED2_FOLDER = ROOT_DIR + "\\MED2\\";
    public static final String MED2_WORD_CARD_HEADERS_FILE = MED2_FOLDER + "WordCardHeaders\\WordCardHeaders.dat";

    public static final String OALD9_FOLDER = ROOT_DIR + "\\OALD9\\";
    public static final String OALD9_IMAGES_FOLDER = OALD9_FOLDER + "Images\\";
    public static final String OALD9_SOUND_DAT_FILE = OALD9_FOLDER + "Sounds\\SoundEn.dat";
    public static final String OALD9_SOUND_IND_FILE = OALD9_FOLDER + "Sounds\\SoundEn.ind";
    public static final String OALD9_TRANSCRIPTIONS_FILE = OALD9_FOLDER + "\\Transcriptions\\Transcriptions.dat";

    public static final String LDOCE6_FOLDER = ROOT_DIR + "\\LDOCE6\\";
    public static final String LDOCE6_IMAGES_FOLDER = LDOCE6_FOLDER + "Images\\";
    public static final String LDOCE6_SOUND_DAT_FILE = LDOCE6_FOLDER + "Sounds\\SoundEn.dat";
    public static final String LDOCE6_SOUND_IND_FILE = LDOCE6_FOLDER + "Sounds\\SoundEn.ind";

    public static final String IRREGULAR_VERBS_FILE = ROOT_DIR + "\\IrregularVerbs\\IrregularVerbs.dat";

    public static final String MNEMONICS_FOLDER = ROOT_DIR + "\\Mnemonics\\";
    public static final String MNEMONICS_IMAGES_FOLDER = MNEMONICS_FOLDER + "Images\\";
    public static final String MNEMONICS_FILE = MNEMONICS_FOLDER + "Mnemonics.dat";

    public static final String WORDS_FILES_FOLDER = configLocation.getWordsFilesFolder();
    public static final String WORDS_FILES_CONTENT_FILE = WORDS_FILES_FOLDER + "_content.json";
    public static final String FILE_NAME_OF_WORDS_FROM_EXCEL_ALIAS = "WordsFromExcel";
    public static String getFileNameOfWordsFromExcel() {
        return configLocation.getFileNameOfWordsFromExcel();
    }

    private static final List<String> WORD_UNNECESSARY_FOR_HANDLING = new ArrayList<String>() {{
        add("i");
        add("she");
        add("he");
        add("it");
        add("is");
        add("a");
        add("the");
        add("to");
        add("at");
        add("on");
        add("in");
        add("into");
        add("for");
        add("of");
    }};

    public static boolean isNecessaryWord(String word) {
        return !(StringUtils.isNumber(word) || Config.WORD_UNNECESSARY_FOR_HANDLING.contains(word.toLowerCase()));
    }
}
