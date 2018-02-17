package com.englishDictionary.webServer.controllers;

import com.englishDictionary.servicesThirdParty.quizlet.Auth2Client;
import com.englishDictionary.servicesThirdParty.quizlet.QuizletAuthenticationURL;
import com.englishDictionary.servicesThirdParty.quizlet.SetsControl;
import com.englishDictionary.utils.json.JsonHelper;
import com.englishDictionary.webServer.HttpServletRequest;
import com.englishDictionary.webServer.HttpServletResponse;
import com.englishDictionary.webServer.annotations.Controller;
import com.englishDictionary.webServer.annotations.RequestMapping;
import com.englishDictionary.webServer.states.ChosenSetWordsByClient;

import java.io.IOException;
import java.util.List;

@Controller(url = "/quizlet/")
public class QuizletController {

    private String accessToken;

    @RequestMapping(url = "isAutorizated.html")
    public void isQuizletAutorizated(HttpServletResponse response) throws IOException {
        response.setContentType("application/json;charset=utf-8");
        response.getOutputStream().write("{\"isQuizletAutorizated\":" + ((accessToken == null) ? false : true) + "}");
    }

    @RequestMapping(url = "authenticationURL.html")
    public void createQuizletAuthenticationURL(HttpServletResponse response) throws IOException {
        if (accessToken == null) {
            response.setContentType("application/json;charset=utf-8");
            QuizletAuthenticationURL authenticationURL = new QuizletAuthenticationURL(Auth2Client.getAuthenticationURL());
            response.getOutputStream().write(JsonHelper.toJson(authenticationURL));
        }
    }

    @RequestMapping(url = "auth.html")
    public void auth(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String code = Auth2Client.parseAuthenticationResponse(request, response);
        accessToken = Auth2Client.getAccessToken(code);
        response.setExternalRedirectURL("/index.html?showQuizletFrame=true");
    }

    @RequestMapping(url = "deleteAllSets.html")
    public void deleteAllSets(HttpServletResponse response) throws IOException {
        DeleteQuizletSetWordsResponse deleteResponse = new DeleteQuizletSetWordsResponse();
        if (accessToken != null) {
            SetsControl.deleteAllSets(accessToken);
            deleteResponse.setSuccessful(true);
        } else {
            deleteResponse.setSuccessful(false);
        }
        response.getOutputStream().write(JsonHelper.toJson(deleteResponse));
    }

    @RequestMapping(url = "exportChosenSet")
    public void exportChosenSet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (accessToken != null) {
            String setName = ChosenSetWordsByClient.INSTANCE.getChosenSetWordsName();
            String fileName = ChosenSetWordsByClient.INSTANCE.getChosenSetWordsFileName();
            // TODO: get setId from export response
            SetsControl.exportSet(accessToken, setName, fileName);
            List<String> setIds = SetsControl.getAllSetsID(accessToken);
            response.setContentType("application/json;charset=utf-8");
            ExportQuizletSetWordsResponse exportResponse = new ExportQuizletSetWordsResponse();
            exportResponse.setSuccessful(true);
            exportResponse.setQuizletIFrameId(setIds.get(0));
            exportResponse.setChosenSetWordsFileName(ChosenSetWordsByClient.INSTANCE.getChosenSetWordsFileName());
            response.getOutputStream().write(JsonHelper.toJson(exportResponse));
        }
    }

    private class DeleteQuizletSetWordsResponse {
        private boolean isSuccessful;
        private String errorName;
        private String errorMessage;

        public DeleteQuizletSetWordsResponse() {
            isSuccessful = false;
            errorName = "";
            errorMessage = "";
        }

        public boolean isSuccessful() {
            return isSuccessful;
        }

        public void setSuccessful(boolean successful) {
            isSuccessful = successful;
        }

        public String getErrorName() {
            return errorName;
        }

        public void setErrorName(String errorName) {
            this.errorName = errorName;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public void setErrorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
        }
    }

    private class ExportQuizletSetWordsResponse {
        private boolean isSuccessful;
        private String chosenSetWordsFileName;
        private String quizletIFrameId;
        private String errorName;
        private String errorMessage;

        public ExportQuizletSetWordsResponse() {
            isSuccessful = false;
            chosenSetWordsFileName = "";
            quizletIFrameId = "-1";
            errorName = "";
            errorMessage = "";
        }

        public boolean isSuccessful() {
            return isSuccessful;
        }

        public void setSuccessful(boolean successful) {
            isSuccessful = successful;
        }

        public String getChosenSetWordsFileName() {
            return chosenSetWordsFileName;
        }

        public void setChosenSetWordsFileName(String chosenSetWordsFileName) {
            this.chosenSetWordsFileName = chosenSetWordsFileName;
        }

        public String getQuizletIFrameId() {
            return quizletIFrameId;
        }

        public void setQuizletIFrameId(String quizletIFrameId) {
            this.quizletIFrameId = quizletIFrameId;
        }

        public String getErrorName() {
            return errorName;
        }

        public void setErrorName(String errorName) {
            this.errorName = errorName;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public void setErrorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
        }
    }

}
