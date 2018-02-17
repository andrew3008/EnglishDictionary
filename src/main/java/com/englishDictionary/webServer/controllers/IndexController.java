package com.englishDictionary.webServer.controllers;

import com.englishDictionary.config.Config;
import com.englishDictionary.resourceReaders.parserSetsWords.ParserSetsWords;
import com.englishDictionary.utils.json.JsonHelper;
import com.englishDictionary.webServer.HttpServletRequest;
import com.englishDictionary.webServer.HttpServletResponse;
import com.englishDictionary.webServer.annotations.Controller;
import com.englishDictionary.webServer.annotations.RequestMapping;
import com.englishDictionary.webServer.annotations.RequestMethod;
import com.englishDictionary.webServer.states.ChosenSetWordsByClient;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.map.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

@Controller(url = "/index/")
public class IndexController {

    private static final int BUFFER_IO_INIT_LENGTH = 100;

    private ByteArrayOutputStream outputStreamBuffer = new ByteArrayOutputStream(BUFFER_IO_INIT_LENGTH);

    @RequestMapping(url = "getContent.html")
    public void getContent(HttpServletResponse response) throws IOException {
        response.setContentType("application/json;charset=utf-8");
        ParserSetsWords.INSTANCE.readContentFile(response, Config.INSTANCE.getWordsFilesDir() + Config.WORDS_FILES_CONTENT_FILE);
    }

    @RequestMapping(url = "getListWords.html")
    public void getListWords(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setContentType("application/json;charset=utf-8");
        ParserSetsWords.INSTANCE.readWordSetFile(response, request.getParameter("fileName"));
    }

    @RequestMapping(url = "saveChosenSetWords", method = RequestMethod.POST)
    public void saveChosenSetWords(HttpServletRequest request) throws IOException {
        outputStreamBuffer.reset();
        request.getContent().getBytes(0, outputStreamBuffer, request.getContent().readableBytes());
        JsonNode rootNode = new ObjectMapper().readTree(outputStreamBuffer.toByteArray());
        ChosenSetWordsByClient selectedSetWords = ChosenSetWordsByClient.INSTANCE;
        selectedSetWords.setQuizletFrameMode((rootNode.get("isQuizletFrameMode").asText().trim().compareToIgnoreCase("true") == 0));
        selectedSetWords.setChosenSetWordsName(rootNode.get("chosenSetWordsName").asText().trim());
        selectedSetWords.setChosenSetWordsFileName(rootNode.get("chosenSetWordsFileName").asText().trim());
    }

    @RequestMapping(url = "restoreChosenSetWords")
    public void restoreChosenSetWords(HttpServletResponse response) throws IOException {
        ChosenSetWordsByClient selectedSetWords = ChosenSetWordsByClient.INSTANCE;
        response.getOutputStream().write(JsonHelper.toJson(selectedSetWords));
    }

}
