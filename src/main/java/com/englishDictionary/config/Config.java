package com.englishDictionary.config;

import httl.util.StringUtils;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
