package com.englishDictionary.utils;

import com.englishDictionary.config.WordsUnnecessaryForHandling;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class SplitterPhraseToWords {

    private static Map<String, String> PUNCTION_MARKS;
    private static Pattern punctionMarksRegExpMatch;
    static {{
        PUNCTION_MARKS = new HashMap<>();
        PUNCTION_MARKS.put(",", "");
        PUNCTION_MARKS.put(";", "");
        PUNCTION_MARKS.put(".", "");
        PUNCTION_MARKS.put(" - ", " ");
        PUNCTION_MARKS.put(":", "");
        PUNCTION_MARKS.put("?", "");
        PUNCTION_MARKS.put("!", "");
        PUNCTION_MARKS.put("/", " ");

        StringBuilder punctionMarksRegExp = new StringBuilder("[");
        for (String puncMark : PUNCTION_MARKS.keySet()) {
            punctionMarksRegExp.append(puncMark.trim());
        }
        punctionMarksRegExp.append("]");
        punctionMarksRegExpMatch = Pattern.compile(punctionMarksRegExp.toString());
    }};

    public static List<String> splitPhrase(String phrase) {
        //if (punctionMarksRegExpMatch.matcher(phrase).matches()) {
            for (Map.Entry<String, String> entry : PUNCTION_MARKS.entrySet()) {
                phrase = phrase.replace(entry.getKey(), entry.getValue());
            }
        //}
        List<String> words = new ArrayList<>();
        for (String word : phrase.trim().split(" ")) {
            if (WordsUnnecessaryForHandling.isNecessaryWord(word)) {
                words.add(word);
            }
        }
        return words;
    }

}
