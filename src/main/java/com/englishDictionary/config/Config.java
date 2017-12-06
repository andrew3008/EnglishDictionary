package com.englishDictionary.config;

import httl.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    private static final Set<String> WORD_UNNECESSARY_FOR_HANDLING = new HashSet<String>() {{
        // Articles
        add("a");
        add("an");
        add("the");

        // To be
        add("am");
        add("is");
        add("are");
        add("was");
        add("were");
        add("be");
        add("been");

        // Pronouns
        add("i");
        add("you");
        add("he");
        add("she");
        add("it");
        add("we");
        add("they");
        add("me");
        add("him");
        add("her");
        add("it");
        add("us");
        add("them");
        add("my");
        add("your");
        add("his");
        add("her");
        add("its");
        add("our");
        add("their");
        add("mine");
        add("yours");
        add("his");
        add("hers");
        add("ours");
        add("theirs");
        add("this");
        add("that");
        add("these");
        add("those");
        add("such");
        add("myself");
        add("yourself");
        add("himself");
        add("herself");
        add("itself");
        add("ourselves");
        add("yourselves");
        add("themselves");
        add("who");
        add("what");
        add("which");
        add("whose");
        add("whoever");
        add("whatever");
        add("whichever");
        add("some");
        add("something");
        add("somebody");
        add("someone");
        add("any");
        add("anything");
        add("anybody");
        add("anyone");
        add("no");
        add("nothing");
        add("nobody");
        add("none");
        add("neither");
        add("other");
        add("another");
        add("all");
        add("each");
        add("both");
        add("either");
        add("every");
        add("everything");
        add("everybody");
        add("everyone");

        // Prepositions
        add("at");
        add("on");
        add("in");
        add("about");
        add("above");
        add("below");
        add("after");
        add("before");
        add("by");
        add("for");
        add("from");
        add("of");
        add("since");
        add("to");
        add("with");
        add("among");
        add("between");

        // Particles
        add("alone");
        add("but");
        add("even");
        add("just");
        add("merely");
        add("only");
        add("solely");
        add("all");
        add("even");
        add("just");
        add("never");
        add("simply");
        add("still");
        add("yet");
        add("exactly");
        add("just");
        add("precisely");
        add("right");
        add("up");
        add("down");
        add("not");
        add("no");
        add("nor");
        add("else");

        // Modal verbs
        add("can");
        add("could");
        add("may");
        add("might");
        add("must");
        add("got");
        add("be");
        add("need");
        add("ought");
        add("should");
        add("would");
        add("shall");
        add("dare");
        add("used");
        add("have");
        add("has");
        add("will");
        add("wont");

        // Conjunctions
        add("and");
        add("or");
        add("but");
        add("as");
        add("like");
        add("far");
        add("long");
        add("soon");
        add("because");
        add("both");
        add("if");
        add("moreover");
        add("neither");
        add("now");
        add("once");
        add("still");
        add("than");
        add("thus");
        add("what");
        add("yet");
        add("with");
        add("without");
        add("within");
        add("according");
        add("beyond");
        add("whereas");
    }};

    public static boolean isNecessaryWord(String word) {
        return !(StringUtils.isNumber(word) || Config.WORD_UNNECESSARY_FOR_HANDLING.contains(word.toLowerCase()));
    }
}
