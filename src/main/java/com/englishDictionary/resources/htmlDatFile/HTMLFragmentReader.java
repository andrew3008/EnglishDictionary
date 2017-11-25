package com.englishDictionary.resources.htmlDatFile;

import com.englishDictionary.utils.ResourceUtils;
import com.englishDictionary.utils.SplitterPhraseToWords;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

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
        for (String word : SplitterPhraseToWords.splitPhrase(phrase)) {
            if (existHTMLByWord(word)) {
                return true;
            }
        }
        return false;
    }

    public String getHTMLByWord(String word) {
        return datFileReader.getHTML(word);
    }

    public void readHTMLByPhrase(OutputStream outputStream, String phrase) {
        boolean isFirstFoundWord = true;
        for (String word : SplitterPhraseToWords.splitPhrase(phrase)) {
            String html = getHTMLByWord(word);
            if (html != null) {
                if (isFirstFoundWord) {
                    isFirstFoundWord = false;
                } else {
                    try {
                        outputStream.write("<br>".getBytes());
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                try {
                    outputStream.write(html.getBytes("UTF-8"));
                } catch (IOException e) {
                    e.printStackTrace();
                }
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
