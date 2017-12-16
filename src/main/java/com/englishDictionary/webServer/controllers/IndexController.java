package com.englishDictionary.webServer.controllers;

import com.englishDictionary.config.Config;
import com.englishDictionary.utils.json.ParserJSonFiles;
import com.englishDictionary.webServer.HttpServletRequest;
import com.englishDictionary.webServer.HttpServletResponse;
import com.englishDictionary.webServer.annotations.Controller;
import com.englishDictionary.webServer.annotations.RequestMapping;

import java.io.IOException;

@Controller(url = "/index/")
public class IndexController {

    @RequestMapping(url = "getContent.html")
    public void getContent(HttpServletResponse response) {
        response.setContentType("application/json;charset=utf-8");
        ParserJSonFiles.readContentFile(response, Config.WORDS_FILES_CONTENT_FILE);
    }

    @RequestMapping(url = "getListWords.html")
    public void getListWords(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setContentType("application/json;charset=utf-8");
        ParserJSonFiles.parseWordsFile(response, request.getParameter("fileName"));
    }

}
