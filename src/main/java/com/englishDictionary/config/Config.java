package com.englishDictionary.config;

import httl.util.StringUtils;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Config  {
    private static ConfigLocation configLocation = ((Boolean.TRUE.toString().compareToIgnoreCase(System.getenv("IS_WORK_STATION")) == 0) ? new ConfigWork() : new ConfigHome());

    public static final int WEB_SERVER_PORT = 8080;

    public static final String RESOURCES_ROOT_DIR = configLocation.getRootDir();

    public static final String COMMON_IMAGES_DIR = RESOURCES_ROOT_DIR + "\\CommonImages\\";

    public static final String WORD_CARDS_IMAGES_DIR = RESOURCES_ROOT_DIR + "\\WordCards\\";

    public static final String DICTIONARIES_DIR = RESOURCES_ROOT_DIR + "\\Dictionaries\\";
    public static final String DIGITAL_DICTIONARIES_DIR = DICTIONARIES_DIR + "DigitalDictionaries\\";

    public static final String LINGVO_UNIVERSAL_DIR = DICTIONARIES_DIR + "\\LingvoUniversal\\";
    public static final String LINGVO_UNIVERSAL_SOUND_DAT_FILE = LINGVO_UNIVERSAL_DIR + "Sounds\\SoundEn.dat";
    public static final String LINGVO_UNIVERSAL_SOUND_IND_FILE = LINGVO_UNIVERSAL_DIR + "Sounds\\SoundEn.ind";

    public static final String MED2_DIR = DICTIONARIES_DIR + "MED2\\";
    public static final String MED2_WORD_CARD_HEADERS_FILE = MED2_DIR + "WordCardHeaders\\WordCardHeaders.dat";

    public static final String OALD9_DIR = DICTIONARIES_DIR + "OALD9\\";
    public static final String OALD9_IMAGES_DIR = OALD9_DIR + "Images\\";
    public static final String OALD9_SOUND_DAT_FILE = OALD9_DIR + "Sounds\\SoundEn.dat";
    public static final String OALD9_SOUND_IND_FILE = OALD9_DIR + "Sounds\\SoundEn.ind";
    public static final String OALD9_TRANSCRIPTIONS_FILE = OALD9_DIR + "\\Transcriptions\\Transcriptions.dat";

    public static final String LDOCE6_DIR = DICTIONARIES_DIR + "LDOCE6\\";
    public static final String LDOCE6_IMAGES_DIR = LDOCE6_DIR + "Images\\";
    public static final String LDOCE6_SOUND_DAT_FILE = LDOCE6_DIR + "Sounds\\SoundEn.dat";
    public static final String LDOCE6_SOUND_IND_FILE = LDOCE6_DIR + "Sounds\\SoundEn.ind";

    public static final String IRREGULAR_VERBS_FILE = DICTIONARIES_DIR + "\\IrregularVerbs\\IrregularVerbs.dat";

    public static final String MNEMONICS_DIR = RESOURCES_ROOT_DIR + "\\Mnemonics\\";
    public static final String MNEMONICS_FLAGS_DIR = MNEMONICS_DIR + "Flags\\";
    public static final String MNEMONICS_IMAGES_DIR = MNEMONICS_DIR + "Images\\";
    public static final String MNEMONICS_FILE = MNEMONICS_DIR + "Mnemonics.dat";

    public static final String FORVO_DIR = RESOURCES_ROOT_DIR + "\\WebServices\\Forvo\\";
    public static final String FORVO_API_KEY = "9abf6bd699950a762f5793dce5a32a56";

    public static final String WORDS_FILES_DIR = configLocation.getWordsFilesFolder();
    public static final String WORDS_FILES_CONTENT_FILE = WORDS_FILES_DIR + "_content.json";
    public static final String FILE_NAME_OF_WORDS_FROM_EXCEL_ALIAS = "WordsFromExcel";
    public static final boolean NEED_EXPORT_WORDS_FROM_EXCEL_TROUGH_BUFFER = (Boolean.TRUE.toString().compareToIgnoreCase(System.getenv("NEED_EXPORT_WORDS_FROM_EXCEL_TROUGH_BUFFER")) == 0);
    public static String getFileNameOfWordsFromExcel() {
        return configLocation.getFileNameOfWordsFromExcel();
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
}
