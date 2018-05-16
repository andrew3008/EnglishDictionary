package com.englishDictionary.config;

import httl.util.StringUtils;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class WordsUnnecessaryForHandling {
    private static final Set<String> WORD_UNNECESSARY_FOR_HANDLING = Stream.of(
            // Articles
            "a",
            "an",
            "the",

            // To be
            "am",
            "is",
            "are",
            "was",
            "were",
            "be",
            "been",

            // Pronouns
            "i",
            "you",
            "he",
            "she",
            "it",
            "we",
            "they",
            "me",
            "him",
            "her",
            "it",
            "us",
            "them",
            "my",
            "your",
            "his",
            "her",
            "its",
            "our",
            "their",
            "mine",
            "yours",
            "his",
            "hers",
            "ours",
            "theirs",
            "this",
            "that",
            "these",
            "those",
            "such",
            "myself",
            "yourself",
            "himself",
            "herself",
            "itself",
            "ourselves",
            "yourselves",
            "themselves",
            "who",
            "what",
            "which",
            "whose",
            "whoever",
            "whatever",
            "whichever",
            "some",
            "something",
            "somebody",
            "someone",
            "any",
            "anything",
            "anybody",
            "anyone",
            "no",
            "nothing",
            "nobody",
            "none",
            "neither",
            "other",
            "another",
            "all",
            "each",
            "both",
            "either",
            "every",
            "everything",
            "everybody",
            "everyone",

            // Prepositions
            "at",
            "on",
            "in",
            "about",
            "above",
            "below",
            "after",
            "before",
            "by",
            "for",
            "from",
            "of",
            "since",
            "to",
            "with",
            "among",
            "between",

            // Particles
            "alone",
            "but",
            "even",
            "just",
            "merely",
            "only",
            "solely",
            "all",
            "even",
            "just",
            "never",
            "simply",
//            "still",
            "yet",
            "exactly",
            "just",
            "precisely",
            "right",
            "up",
            "down",
            "not",
            "no",
            "nor",
            "else",

            // Modal verbs
            "can",
            "could",
            "may",
            "might",
            "must",
            "got",
            "be",
            "need",
            "ought",
            "should",
            "would",
            "shall",
            "dare",
            "used",
            "have",
            "has",
            "will",
            "wont",

            // Conjunctions
            "and",
            "or",
            "but",
            "as",
            "like",
            "far",
            "long",
            "soon",
            "because",
            "both",
            "if",
            "moreover",
            "neither",
            "now",
            "once",
//            "still",
            "than",
            "thus",
            "what",
            "yet",
            "with",
            "without",
//            "within",
            "according"
    ).collect(Collectors.toSet());

    public static boolean isNecessaryWord(String word) {
        return !(WordsUnnecessaryForHandling.WORD_UNNECESSARY_FOR_HANDLING.contains(word.toLowerCase()) || StringUtils.isNumber(word));
    }
}
