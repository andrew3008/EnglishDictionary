package com.englishDictionary.webServer.controllers;

public class CommentsListWords {
    private boolean haseComments;
    private String commentsFileName;
    private String noCommentsMessage;

    public boolean isHaseComments() {
        return haseComments;
    }

    public void setHaseComments(boolean haseComments) {
        this.haseComments = haseComments;
    }

    public String getCommentsFileName() {
        return commentsFileName;
    }

    public void setCommentsFileName(String commentsFileName) {
        this.commentsFileName = commentsFileName;
    }

    public String getNoCommentsMessage() {
        return noCommentsMessage;
    }

    public void setNoCommentsMessage(String noCommentsMessage) {
        this.noCommentsMessage = noCommentsMessage;
    }
}
