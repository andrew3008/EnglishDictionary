package com.englishDictionary.webServices.forvo;

public class ForvoCard {
    private boolean male;
    private String user;
    private String userProfile;
    private String country;
    private String flagFileName;
    int totalVotes;
    int positiveVotes;
    int negativeVotes;
    private String addTime;
    private String pathmp3;

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

    public String getAddTime() {
        return addTime;
    }

    public void setAddTime(String addTime) {
        this.addTime = addTime;
    }

    public String getPathmp3() {
        return pathmp3;
    }

    public void setPathmp3(String pathmp3) {
        this.pathmp3 = pathmp3;
    }
}
