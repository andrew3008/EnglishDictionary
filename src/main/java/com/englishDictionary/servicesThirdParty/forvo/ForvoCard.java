package com.englishDictionary.servicesThirdParty.forvo;

import com.englishDictionary.webServer.ByteArrayOutputStream;

public class ForvoCard {
    private boolean male;
    private String user;
    private String userProfile;
    private String country;
    private String flagFileName;
    int totalVotes;
    int positiveVotes;
    int negativeVotes;
    private String pathmp3;
    private ByteArrayOutputStream audioStreamBuffer;

    private static int AUDIO_STREAM_BUFFER_SIZE_DEFAULT = 2048;

    public ForvoCard() {
        audioStreamBuffer = new ByteArrayOutputStream(AUDIO_STREAM_BUFFER_SIZE_DEFAULT);
    }

    public boolean isMale() {
        return male;
    }

    public void setMale(boolean male) {
        this.male = male;
    }

    public String getUser() {
        return user;
    }

    public void setUser(String user) {
        this.user = user;
    }

    public String getUserProfile() {
        return userProfile;
    }

    public void setUserProfile(String userProfile) {
        this.userProfile = userProfile;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public String getFlagFileName() {
        return flagFileName;
    }

    public void setFlagFileName(String flagFileName) {
        this.flagFileName = flagFileName;
    }

    public int getTotalVotes() {
        return totalVotes;
    }

    public void setTotalVotes(int totalVotes) {
        this.totalVotes = totalVotes;
    }

    public int getPositiveVotes() {
        return positiveVotes;
    }

    public void setPositiveVotes(int positiveVotes) {
        this.positiveVotes = positiveVotes;
    }

    public int getNegativeVotes() {
        return negativeVotes;
    }

    public void setNegativeVotes(int negativeVotes) {
        this.negativeVotes = negativeVotes;
    }

    public String getPathmp3() {
        return pathmp3;
    }

    public void setPathmp3(String pathmp3) {
        this.pathmp3 = pathmp3;
    }

    public ByteArrayOutputStream getAudioStreamBuffer() {
        return audioStreamBuffer;
    }
}
