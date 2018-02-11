package com.englishDictionary.webServer.controllers;

import com.englishDictionary.config.Config;
import com.englishDictionary.utils.json.ParserSetsWords;
import com.englishDictionary.webServer.HttpServletRequest;
import com.englishDictionary.webServer.HttpServletResponse;
import com.englishDictionary.webServer.annotations.Controller;
import com.englishDictionary.webServer.annotations.RequestMapping;
import com.englishDictionary.webServer.annotations.RequestMethod;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.map.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

@Controller(url = "/index/")
public class IndexController {

    private static final int BUFFER_IO_INIT_LENGTH = 200;
    private final static String RESTORE_CHOSEN_SET_RESPONSE_TEMPLATE = "{\"isQuizletFrameMode\":\"%s\",\"chosenSetWordsName\":\"%s\"}";

    private boolean isQuizletFrameMode = false;
    private String chosenSetWordsName;
    private ByteArrayOutputStream outputStreamBuffer = new ByteArrayOutputStream(BUFFER_IO_INIT_LENGTH);

    @RequestMapping(url = "getContent.html")
    public void getContent(HttpServletResponse response) {
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
        request.getContent().getBytes(0, outputStreamBuffer, request.getContent().readableBytes());
        JsonNode rootNode = new ObjectMapper().readTree(outputStreamBuffer.toByteArray());
        isQuizletFrameMode = (rootNode.get("isQuizletFrameMode").asText().trim().compareToIgnoreCase("true") == 0);
        chosenSetWordsName = rootNode.get("chosenSetWordsName").asText().trim();
    }

    @RequestMapping(url = "restoreChosenSetWords")
    public void restoreChosenSetWords(HttpServletResponse response) throws IOException {
        System.out.println("[restoreChosenSetWords] String.format(RESTORE_CHOSEN_SET_RESPONSE_TEMPLATE, isQuizletFrameMode, chosenSetWordsName):" + String.format(RESTORE_CHOSEN_SET_RESPONSE_TEMPLATE, isQuizletFrameMode, chosenSetWordsName));
        response.getOutputStream().write(String.format(RESTORE_CHOSEN_SET_RESPONSE_TEMPLATE, isQuizletFrameMode, chosenSetWordsName));
    }

}
