package com.englishDictionary.webServer.states;

public class ChosenSetWordsByClient {

    public static final ChosenSetWordsByClient INSTANCE = new ChosenSetWordsByClient();

    private boolean isQuizletFrameMode;
    private String chosenSetWordsName;
    private String chosenSetWordsFileName;

    private ChosenSetWordsByClient() {
    }

    public boolean isQuizletFrameMode() {
        return isQuizletFrameMode;
    }

    public void setQuizletFrameMode(boolean quizletFrameMode) {
        isQuizletFrameMode = quizletFrameMode;
    }

    public String getChosenSetWordsName() {
        return chosenSetWordsName;
    }

    public void setChosenSetWordsName(String chosenSetWordsName) {
        this.chosenSetWordsName = chosenSetWordsName;
    }

    public String getChosenSetWordsFileName() {
        return chosenSetWordsFileName;
    }

    public void setChosenSetWordsFileName(String chosenSetWordsFileName) {
        this.chosenSetWordsFileName = chosenSetWordsFileName;
    }

}
