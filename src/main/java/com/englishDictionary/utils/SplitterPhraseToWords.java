package com.englishDictionary.utils;

import com.englishDictionary.config.Config;

import java.util.ArrayList;
import java.util.List;

public class SplitterPhraseToWords {

    public static List<String> splitPhrase(String phrase) {
        List<String> words = new ArrayList<>();
        for (String word : phrase.trim().split(" ")) {
            if (Config.isNecessaryWord(word)) {
                words.add(word);
            }
        }
        return words;
    }

}
