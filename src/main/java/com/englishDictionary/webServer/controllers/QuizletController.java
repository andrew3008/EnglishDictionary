package com.englishDictionary.webServer.controllers;
import com.englishDictionary.servicesThirdParty.quizlet.QuizletAuthenticationURL;
import com.englishDictionary.utils.json.JsonHelper;
import com.englishDictionary.servicesThirdParty.quizlet.Auth2Client;
import com.englishDictionary.servicesThirdParty.quizlet.SetsControl;
import com.englishDictionary.webServer.annotations.Controller;
import com.englishDictionary.webServer.annotations.RequestMapping;
import com.englishDictionary.webServer.HttpServletRequest;
import com.englishDictionary.webServer.HttpServletResponse;

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
        // TODO: Make shower errors through view
        response.setExternalRedirectURL("/index.html?showQuizletFrame=true");
    }

    @RequestMapping(url = "deleteAllSets.html")
    public void deleteAllSets() {
        if (accessToken != null) {
            SetsControl.deleteAllSets(accessToken);
        }
    }

    @RequestMapping(url = "exportSet.html")
    public void exportSet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (accessToken != null) {
            String setName = request.getParameter("setName");
            String fileName = request.getParameter("fileName");
            SetsControl.exportSet(accessToken, setName, fileName);
            List<String> setIds = SetsControl.getAllSetsID(accessToken);
            response.setContentType("application/json;charset=utf-8");
            response.getOutputStream().write("{\"setId\":\"" + setIds.get(0) + "\"}");
        }
    }

}
