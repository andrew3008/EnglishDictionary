package com.englishDictionary.webServices.lingualeo;

/**
 * Created by Andrew on 9/20/2016.
 */
public class LinguaLeoApiSecurityCooker {
    private String linguaLeoUID = "";
    private String lang = "";
    private String userId = "";
    private String servid = "";
    private String remember = "";
    private String firstSeen = "";

    public String build() {
        //String format = "lingualeouid=%1s; lang=%2s; browser-plugins-msg-hide=1; userid=%3s; servid=%4s; remember=%5s; firstseen=%6s";
        String format = "lingualeouid=%1s; lang=%2s; userid=%3s; servid=%4s; remember=%5s; firstseen=%6s";
        return String.format(format, linguaLeoUID, lang, userId, servid, remember, firstSeen);
    }

    public void setLinguaLeoUID(String linguaLeoUID) {
        this.linguaLeoUID = linguaLeoUID;
    }

    public void setLang(String lang) {
        this.lang = lang;
    }

    public String getLang() {
        return lang;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public void setServid(String servid) {
        this.servid = servid;
    }

    public void setRemember(String remember) {
        this.remember = remember;
    }

    public void setFirstSeen(String firstSeen) {
        this.firstSeen = firstSeen;
    }
}
