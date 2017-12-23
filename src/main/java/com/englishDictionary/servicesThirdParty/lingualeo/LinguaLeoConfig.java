package com.englishDictionary.servicesThirdParty.lingualeo;

/**
 * Created by Andrew on 8/20/2016.
 */
public class LinguaLeoConfig {
    public final static String EMAIL = "andrew3007@kabit.ru";
    public final static String PASSWORD = "fgkfgjkfUIrejkj545";
    //public final static String ENLISH_DICTIONARY_APPLICATION_SET_ID = "8079";

    public final static String HOST_API = "http://api.lingualeo.com";
    public final static String CHECK_AUTHORIZED_URL = HOST_API + "/isauthorized";
    public final static String LOGIN_URL = HOST_API + "/api/login";
    public final static String EXPORT_WORD_URL = HOST_API + "/addWord";
    public final static String EXPORT_WORDS_URL = HOST_API + "/addWords";
    public final static String IMPORT_URL = HOST_API + "/getTranslations";

    public static final String GET_DICTIONARIES = "http://lingualeo.com/ru/userdict3/getWordSets"; //GET
    public static final String CREATE_DICTIONARY = "http://lingualeo.com/userdict3/createWordSet"; //POST
    public static final String GET_WORDS_FROM_DICTIONARY = "http://lingualeo.com/userdict/json"; // POST

    public static final String DELETE_DICTIONARY = "http://lingualeo.com/userdict3/deleteWordSet"; // POST
    //public static final String DELETE_WORDS_FROM_DICTIONARY = "http://lingualeo.com/ru/userdict3/deleteWords"; //POST
    //public static final String DELETE_WORDS_FROM_DICTIONARY = "http://lingualeo.com/userdict3/deleteWords?all=1&groupId=8079&filter=all&search=&wordType=0&delete_source=dictionary_toolbar&wordIds_length=";
    public static final String DELETE_WORDS_FROM_DICTIONARY = "https://lingualeo.com/userdict3/deleteWords?all=1&groupId=dictionary&filter=all&search=&wordType=0&delete_source=dictionary_toolbar&wordIds_length=";
}
