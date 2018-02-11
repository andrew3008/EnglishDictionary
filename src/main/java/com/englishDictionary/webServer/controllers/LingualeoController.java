package com.englishDictionary.webServer.controllers;

import com.englishDictionary.utils.json.ParserSetsWords;
import com.englishDictionary.webServer.annotations.Controller;
import com.englishDictionary.webServer.annotations.RequestMapping;
import com.englishDictionary.webServer.HttpServletRequest;
import com.englishDictionary.servicesThirdParty.lingualeo.LinguaLeoClient;
import com.englishDictionary.servicesThirdParty.lingualeo.LinguaLeoWordInfo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Created by Andrew on 9/10/2017.
 */
@Controller(url = "/lingualeo/")
public class LingualeoController {

    private LinguaLeoClient lingualeoClient = new LinguaLeoClient();

    @RequestMapping(url = "exportCurrentSet.html")
    public void exportWordsToLingualeo(HttpServletRequest request) throws IOException {
        String fileName = request.getParameter("fileName");

        if (!lingualeoClient.authorization()) {
            // TODO: Send to client Internel_Server_Err with message "Not authetorization yet"
            System.out.println("Not authetorization on Lingualeo");
            return;
        }

        lingualeoClient.deleteAllWords();

        Map<String, String> words = ParserSetsWords.INSTANCE.readWordSetFile(fileName);
        List<LinguaLeoWordInfo> wordInfos = new ArrayList<>();
        for (Map.Entry<String, String> wordEntry : words.entrySet()) {
            if (!wordEntry.getKey().isEmpty()) {
                wordInfos.add(new LinguaLeoWordInfo(wordEntry.getKey(), wordEntry.getValue()));
            }
        }
        lingualeoClient.addWords(wordInfos);
    }

}
