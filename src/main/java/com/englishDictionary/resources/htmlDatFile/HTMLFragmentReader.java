package com.englishDictionary.resources.htmlDatFile;

import com.englishDictionary.config.Config;
import com.englishDictionary.utils.ResourceUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Set;

public class HTMLFragmentReader {
    private HTMLDatFileReader datFileReader;
    private String fileName;

    public HTMLFragmentReader(String fullFileName) {
        this.fileName = ResourceUtils.getFileNameWithoutExtnFromPath(fullFileName);
        datFileReader = new HTMLDatFileReader();
        try {
            datFileReader.open(fullFileName);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public String getFileName() {
        return fileName;
    }

    public boolean existHTMLByWord(String word) {
        return datFileReader.existHTML(word);
    }

    public boolean existHTMLByPhrase(String phrase) {
        for (String word : phrase.trim().split(" ")) {
            if (existHTMLByWord(word)) {
                return true;
            }
        }
        return false;
    }

    public String getHTMLByWord(String word) {
        return datFileReader.getHTML(word);
    }

    public String getHTMLByPhrase(String word) {
        word = word.trim();
        if (word.indexOf(' ') == -1) {
            String html = getHTMLByWord(word);
            if (html != null) {
                return html;
            }
        }

        String resHTML = new String();
        boolean isLastWord = false;
        while (true) {
            String wordCheck = null;
            if (word.indexOf(' ') == -1) {
                wordCheck = word;
                isLastWord = true;
            } else {
                String[] arrWords = word.split(" ");
                wordCheck = arrWords[0];
                word = arrWords[1];
            }

            String html = getHTMLByWord(wordCheck);
            if ((html != null) && Config.isNecessaryWord(wordCheck)) {
                if (resHTML.length() != 0) {
                    resHTML = resHTML.concat("\\n");
                }
                resHTML = resHTML.concat(html);
            }

            if (isLastWord) {
                return resHTML;
            }
        }
    }

    public void readHTMLByWord(OutputStream outputStream, String word) {
        datFileReader.readHTMLByWord(outputStream, word);
    }

    public List<String> searchLinkWords(String heardWord) {
        return datFileReader.searchLinkWords(heardWord);
    }

    public void closeFile() throws IOException {
        datFileReader.closeFile();
    }
}
