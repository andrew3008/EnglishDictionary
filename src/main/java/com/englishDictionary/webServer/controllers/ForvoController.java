package com.englishDictionary.webServer.controllers;

import com.englishDictionary.config.Config;
import com.englishDictionary.webServices.forvo.Forvo;
import com.englishDictionary.webServices.forvo.ForvoCard;
import com.englishDictionary.utils.httl.HttlEngineKeeper;
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

@Controller(url = "/forvoVoicesCard/")
public class ForvoController {

    private Forvo forvo = null;

    public ForvoController() {
        forvo = new Forvo();
    }

    @RequestMapping(url = "show.html")
    public void showVoiceCardsFromForvo(HttpServletRequest request, HttpServletResponse response) throws ParseException, IOException {
        String phrase = request.getParameter("word");
        String[] words = phrase.trim().split(" ");
        List<String> headWords = new ArrayList<>();
        Map<String, List<ForvoCard>> forvoCardsGroupByWords = new HashMap<String, List<ForvoCard>>();
        for (String word : words) {
            if (!Config.isNecessaryWord(word)) {
                continue;
            }

            List<ForvoCard> forvoCards = forvo.getForvoCards(word, response);
            if (!forvoCards.isEmpty()) {
                headWords.add(word);
                forvoCardsGroupByWords.put(word, forvoCards);
            }
        }
        Map<String, Object> viewParameters = new HashMap<>();
        viewParameters.put("phrase", phrase);
        viewParameters.put("headWords", headWords);
        viewParameters.put("forvoCardsGroupByWords", forvoCardsGroupByWords);
        HttlEngineKeeper.engine.getTemplate("forvoPage.httl").render(viewParameters, response.getOutputStream());
    }

}
