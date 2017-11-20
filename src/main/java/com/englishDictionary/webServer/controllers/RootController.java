package com.englishDictionary.webServer.controllers;

import com.englishDictionary.utils.httl.HttlEngineKeeper;
import com.englishDictionary.webServer.HttpServletResponse;
import com.englishDictionary.webServer.annotations.Controller;
import com.englishDictionary.webServer.annotations.RequestMapping;
import io.netty.handler.codec.http.HttpResponseStatus;

import java.io.IOException;
import java.text.ParseException;

/**
 * Created by Andrew on 11/5/2017.
 */
@Controller
public class RootController {

    @RequestMapping(url = "/")
    public void getRoot(HttpServletResponse response) throws IOException, ParseException {
        response.setExternalRedirectURL("/index.html");
    }

    @RequestMapping(url = "/index.html")
    public void getMainPage(HttpServletResponse response) throws IOException, ParseException {
        HttlEngineKeeper.engine.getTemplate("mainPage.httl").render(response.getOutputStream());
    }

    @RequestMapping(url = "/\"")
    public void handleEmpty(HttpServletResponse response) {
        response.setStatus(HttpResponseStatus.OK);
    }

}
