package com.englishDictionary.utils.json;

import com.englishDictionary.config.Config;
import com.englishDictionary.config.EnvironmentType;
import com.englishDictionary.resourceReaders.htmlDatFile.HTMLFragmentReader;
import com.englishDictionary.servicesThirdParty.excel.BufferListOfWordsFromExcel;
import com.englishDictionary.utils.csv.CSVParser;
import com.englishDictionary.webServer.ByteArrayOutputStream;
import com.englishDictionary.webServer.HttpServletResponse;
import com.englishDictionary.webServer.utils.SEDHttpClient;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.map.ObjectMapper;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

/**
 * Created by Andrew on 1/10/2016.
 */
public class ParserSetsWords {
    public static final ParserSetsWords INSTANCE = new ParserSetsWords();

    private static final int BUFFER_IO_SIZE = 10240;
    private static byte[] bufferIO = new byte[BUFFER_IO_SIZE];
    private static final String CSV_GROUP_NAME_PROPERTY = "csvGroupName";
    private static HTMLFragmentReader transcription;
    private static HTMLFragmentReader mnemonics;

    public void readContentFile(HttpServletResponse response, String fileName) {
        if (EnvironmentType.OPEN_SHIFT_CLUSTER == Config.INSTANCE.getEnvironmentType()) {
            SEDHttpClient httpClient = new SEDHttpClient();
            Map<String, String> headers = new HashMap<>();
            headers.put("Cookie", "yandexuid=137029991514971623; lah=;");
            headers.put("Host", "webdav.yandex.ru");
            headers.put("Accept", "application/json;charset=utf-8");
            headers.put("Authorization", "OAuth AQAEA7qgySSkAAS9YffJNgqU1k9qp75Zd9Dq4WY");
            SEDHttpClient.HttpRequestResponse responce = httpClient.sendGetRequest("http://webdav.yandex.ru/" + fileName, headers);
            try {
                response.getOutputStream().write(responce.getContent());
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else {
            try {
                InputStream fileIS = new FileInputStream(fileName);
                OutputStream responceOS = response.getOutputStream();
                for (int n = 0; n != -1; n = fileIS.read(bufferIO, 0, BUFFER_IO_SIZE)) {
                    responceOS.write(bufferIO, 0, n);
                }
                fileIS.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public String getFullFileName(String fileName) {
        if (Config.FILE_NAME_OF_WORDS_FROM_EXCEL_ALIAS.equals(fileName)) {
            return Config.INSTANCE.getFileNameOfWordsFromExcel();
        } else {
            return Config.INSTANCE.getWordsFilesDir() + "\\" + fileName + ".json";
        }
    }

    public void readWordSetFile(HttpServletResponse response, String fileName) throws IOException {
        ByteArrayOutputStream responceWriter = response.getOutputStream();
        if (transcription == null) {
            transcription = new HTMLFragmentReader(Config.INSTANCE.getOALD9TranscriptionsFilePath());
            mnemonics = new HTMLFragmentReader(Config.INSTANCE.getMnemonicsFilePath());
        }

        byte[] json = null;
        if (EnvironmentType.OPEN_SHIFT_CLUSTER == Config.INSTANCE.getEnvironmentType()) {
            SEDHttpClient httpClient = new SEDHttpClient();
            Map<String, String> headers = new HashMap<>();
            headers.put("Cookie", "yandexuid=137029991514971623; lah=;");
            headers.put("Host", "webdav.yandex.ru");
            headers.put("Accept", "application/json;charset=utf-8");
            headers.put("charset", "utf-8");
            headers.put("Authorization", "OAuth AQAEA7qgySSkAAS9YffJNgqU1k9qp75Zd9Dq4WY");
            SEDHttpClient.HttpRequestResponse requestResponse = httpClient.sendGetRequest("http://webdav.yandex.ru/" + fileName + ".json", headers);
            json = requestResponse.getContent();
        }

        boolean isFirstWord = true;
        responceWriter.write("[");
        for (WordCardDescription wordCardDescription : readWordSet(fileName, json)) {
            if (isFirstWord) {
                isFirstWord = false;
            } else {
                responceWriter.write(",");
            }
            writeWordDescription(responceWriter, wordCardDescription);
        }
        responceWriter.write("]");
    }

    public Map<String, String> readWordSetFile(String fileName) throws IOException {
        BufferedReader reader;
        try {
            reader = createReaderOfSetWords(fileName);
        } catch (IOException e) {
            e.printStackTrace();
            return Collections.EMPTY_MAP;
        }

        Map<String, String> mapWordNameToTranslate = new LinkedHashMap<>();
        for (WordCardDescription description : readWordSet(fileName, null)) {
            mapWordNameToTranslate.put(description.word, description.translation);
        }

        if (reader != null) {
            try {
                reader.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        return mapWordNameToTranslate;
    }


    /**********************************************************************************************************************************************************/
    /*                                                                  Private methods                                                                       */
    /**********************************************************************************************************************************************************/
    private class WordCardDescription {
        String groupName;
        String word;
        String translation;
        String examples;

        WordCardDescription(String groupName, String word, String translation, String examples) {
            this.groupName = groupName;
            this.word = word;
            this.translation = translation;
            this.examples = examples;
        }
    }

    private BufferedReader createReaderOfSetWords(String fileName) throws FileNotFoundException, UnsupportedEncodingException {
        InputStream inputStream;
        if (Config.INSTANCE.isNeedExportWordsFromExcelThroughBuffer() && Config.FILE_NAME_OF_WORDS_FROM_EXCEL_ALIAS.equals(fileName)) {
            inputStream = BufferListOfWordsFromExcel.INSTANCE.getInputStream();
        } else {
            String fullFileName;
            if (Config.FILE_NAME_OF_WORDS_FROM_EXCEL_ALIAS.equals(fileName)) {
                fullFileName = Config.INSTANCE.getFileNameOfWordsFromExcel();
            } else {
                fullFileName = Config.INSTANCE.getWordsFilesDir() + "\\" + fileName + ".json";
            }
            inputStream = new FileInputStream(fullFileName);
        }
        return new BufferedReader(new InputStreamReader(inputStream, Config.CHARSET));
    }

    private List<WordCardDescription> readWordSet(String fileName, byte[] json) throws IOException {
        boolean isListWordInCSV = Config.INSTANCE.isNeedExportWordsFromExcelThroughBuffer() && Config.FILE_NAME_OF_WORDS_FROM_EXCEL_ALIAS.equals(fileName);
        if (isListWordInCSV) {
            return readWordSetFromCSV(fileName);
        } else {
            return readWordSetFromJSon(fileName, json);
        }
    }

    private List<WordCardDescription> readWordSetFromCSV(String fileName) throws IOException {
        List<WordCardDescription> wordCardDescriptions = new ArrayList<>();
        String groupName = "";
        BufferedReader reader = createReaderOfSetWords(fileName);
        Scanner scanner = new Scanner(reader);
        while (scanner.hasNext()) {
            List<String> line = CSVParser.parseLine(scanner.nextLine());
            if (line.isEmpty()) {
                continue;
            }

            String word = line.get(0).trim();
            String translation = line.get(1).trim();
            String examples = line.get(2).trim();
            if (CSV_GROUP_NAME_PROPERTY.equals(word)) {
                groupName = translation;
            } else {
                wordCardDescriptions.add(new WordCardDescription(groupName, word, translation, examples));
            }
        }
        scanner.close();
        return wordCardDescriptions;
    }

    private List<WordCardDescription> readWordSetFromJSon(String fileName, byte[] json) throws IOException {
        if ((json == null || json.length == 0) && (fileName == null || fileName.isEmpty())) {
            return Collections.EMPTY_LIST;
        }

        List<WordCardDescription> wordCardDescriptions = new ArrayList<>();
        JsonNode rootNode = null;
        BufferedReader reader = null;
        if (json == null) {
            reader = createReaderOfSetWords(fileName);
            rootNode = new ObjectMapper().readTree(reader);
        } else {
            rootNode = new ObjectMapper().readTree(json);
        }

        Iterator<Map.Entry<String, JsonNode>> itGroupWordNode = rootNode.getFields();
        while (itGroupWordNode.hasNext()) {
            Map.Entry<String, JsonNode> groupWordNode = itGroupWordNode.next();
            String groupName = groupWordNode.getKey();
            Iterator<JsonNode> itWordCardNode = groupWordNode.getValue().getElements();
            while (itWordCardNode.hasNext()) {
                JsonNode wordCardNode = itWordCardNode.next();
                wordCardDescriptions.add(new WordCardDescription(
                        groupName,
                        wordCardNode.get("Word").asText().trim(),
                        wordCardNode.get("Translation").asText().trim(),
                        wordCardNode.get("Examples").asText().trim()));
            }
        }
        if (reader != null) {
            reader.close();
        }
        return wordCardDescriptions;
    }

    private void writeWordDescription(ByteArrayOutputStream responceWriter, WordCardDescription description) {
        try {
            responceWriter.write("{");
            responceWriter.write("\"groupWords\":\"" + description.groupName + "\",");
            responceWriter.write("\"word\":\"" + description.word + "\",");
            responceWriter.write("\"haseMnemonics\":" + mnemonics.existHTMLByPhrase(description.word) + ",");
            responceWriter.write("\"transcription\":\"");
            transcription.readHTMLByPhrase(responceWriter, description.word);
            responceWriter.write("\",");
            responceWriter.write("\"translation\":\"" + description.translation + "\",");
            responceWriter.write("\"examples\":\"" + description.examples + "\"");
            responceWriter.write("}");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}
