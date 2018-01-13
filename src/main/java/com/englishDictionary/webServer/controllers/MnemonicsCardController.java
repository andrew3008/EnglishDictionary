package com.englishDictionary.webServer.controllers;

import com.englishDictionary.config.Config;
import com.englishDictionary.utils.httl.HttlEngineKeeper;
import com.englishDictionary.resourceReaders.htmlDatFile.HTMLFragmentReader;
import com.englishDictionary.webServer.annotations.Controller;
import com.englishDictionary.webServer.annotations.RequestMapping;
import com.englishDictionary.webServer.HttpServletRequest;
import com.englishDictionary.webServer.HttpServletResponse;

import java.io.IOException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Controller(url = "/mnemonicCard/")
public class MnemonicsCardController {

    private HTMLFragmentReader mnemonics;

    @RequestMapping(url = "show.html")
    public void showMnemonicsCard(HttpServletRequest request, HttpServletResponse response) throws IOException, ParseException {
        if (mnemonics == null) {
            mnemonics = new HTMLFragmentReader(Config.INSTANCE.getMnemonicsFilePath());
        }

        String phrase = request.getParameter("word");
        String[] words = phrase.trim().split(" ");
        List<String> headWords = new ArrayList<>();
        Map<String, String> mnemonicsCardsGroupByWords = new HashMap<>();
        for (String word : words) {
            if (!Config.isNecessaryWord(word)) {
                continue;
            }

            String mnemonicsCards = mnemonics.getHTMLByWord(word);
            if (mnemonicsCards != null) {
                headWords.add(word);
                mnemonicsCardsGroupByWords.put(word, mnemonicsCards);
            }
        }
        Map<String, Object> viewParameters = new HashMap<>();
        viewParameters.put("phrase", phrase);
        viewParameters.put("headWords", headWords);
        viewParameters.put("mnemonicsCardsGroupByWords", mnemonicsCardsGroupByWords);
        HttlEngineKeeper.engine.getTemplate("mnemonicsPage.httl").render(viewParameters, response.getOutputStream());
    }

}
