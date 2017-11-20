package com.englishDictionary.webServices.quizlet;

class Auth2ClientAccount {
    static String CLIENT_ID = "nUuXffHUWb";
    static String CLIENT_SECRET = "WNXYnYgKb2eN4DZ4JKbmtR";
    static String REDIRECT_URI = "http://localhost:8080/quizlet/auth.html";
    static String QUIZLET_AUTHORIZE_URL = "https://quizlet.com/authorize";
    static String QUIZLET_ACCESS_TOKEN_URL = "https://api.quizlet.com/oauth/token";

    static String SCOPE = "read\u0020write_set\u0020write_group";
    static String STATE = "justTakALook";
}
