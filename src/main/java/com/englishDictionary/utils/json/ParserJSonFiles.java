package com.englishDictionary.utils.json;

import com.englishDictionary.config.Config;
import com.englishDictionary.resources.htmlDatFile.HTMLFragmentReader;
import com.englishDictionary.webServer.ByteArrayOutputStream;
import com.englishDictionary.webServer.HttpServletResponse;
import com.englishDictionary.webServices.excel.BufferListOfWordsFromExcel;
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
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Created by Andrew on 1/10/2016.
 */
public class ParserJSonFiles {
    private static final int BUFFER_IO_SIZE = 10240;
    private static byte[] bufferIO = new byte[BUFFER_IO_SIZE];

    private static HTMLFragmentReader transcription = new HTMLFragmentReader(Config.OALD9_TRANSCRIPTIONS_FILE);
    private static HTMLFragmentReader mnemonics = new HTMLFragmentReader(Config.MNEMONICS_FILE);

    static public void readContentFile(HttpServletResponse response, String fileName) {
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

    static public void parseWordsFile(HttpServletResponse response, String fileName) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        BufferedReader reader;
        JsonNode rootNode;
        ByteArrayOutputStream responceWriter;
        try {
            reader = createReaderOfSetWords(fileName);
            rootNode = mapper.readTree(reader);
            responceWriter = response.getOutputStream();
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }

        responceWriter.write("[");
        boolean isFirstWord = true;
        Iterator<Map.Entry<String, JsonNode>> itGroupWordNode = rootNode.getFields();
        while (itGroupWordNode.hasNext()) {
            Map.Entry<String, JsonNode> groupWordNode = itGroupWordNode.next();
            String groupName = groupWordNode.getKey();

            Iterator<JsonNode> itWordCardNode = groupWordNode.getValue().getElements();
            while (itWordCardNode.hasNext()) {
                JsonNode wordCardNode = itWordCardNode.next();
                if (isFirstWord) {
                    isFirstWord = false;
                } else {
                    responceWriter.write(",");
                }

                responceWriter.write("{");
                responceWriter.write("\"groupWords\":\"" + groupName + "\",");
                String word = wordCardNode.get("Word").asText().trim();
                responceWriter.write("\"word\":\"" + word + "\",");
                responceWriter.write("\"haseMnemonics\":" + mnemonics.existHTMLByPhrase(word) + ",");
                responceWriter.write("\"transcription\":\"");
                transcription.readHTMLByPhrase(responceWriter, word);
                responceWriter.write("\",");
                responceWriter.write("\"translation\":\"" + wordCardNode.get("Translation").asText().trim() + "\",");
                responceWriter.write("\"examples\":\"" + wordCardNode.get("Examples").asText() + "\"");
                responceWriter.write("}");
            }
        }
        responceWriter.write("]");
        try {
            reader.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    static public Map<String, String> readWordsForSet(String fileName) {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode rootNode;
        BufferedReader reader;
        try {
            reader = createReaderOfSetWords(fileName);
            rootNode = mapper.readTree(reader);
        } catch (IOException e) {
            e.printStackTrace();
            return Collections.EMPTY_MAP;
        }

        Map<String, String> mapWordNameToTranslate = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> itGroupWordNode = rootNode.getFields();
        while (itGroupWordNode.hasNext()) {
            Map.Entry<String, JsonNode> groupWordNode = itGroupWordNode.next();
            Iterator<JsonNode> itWordCardNode = groupWordNode.getValue().getElements();
            while (itWordCardNode.hasNext()) {
                JsonNode wordCardNode = itWordCardNode.next();
                mapWordNameToTranslate.put(wordCardNode.get("Word").asText().trim(), wordCardNode.get("Translation").asText().trim());
            }
        }

        try {
            reader.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

        return mapWordNameToTranslate;
    }

    private static BufferedReader createReaderOfSetWords(String fileName) throws FileNotFoundException, UnsupportedEncodingException {
        InputStream inputStream;
        if (Config.NEED_EXPORT_WORDS_FROM_EXCEL_TROUGH_BUFFER && Config.FILE_NAME_OF_WORDS_FROM_EXCEL_ALIAS.equals(fileName)) {
            inputStream = BufferListOfWordsFromExcel.INSTANCE.getInputStream();
        } else {
            String fullFileName;
            if (Config.FILE_NAME_OF_WORDS_FROM_EXCEL_ALIAS.equals(fileName)) {
                fullFileName = Config.getFileNameOfWordsFromExcel();
            } else {
                fullFileName = Config.WORDS_FILES_FOLDER + "\\" + fileName + ".json";
            }
            inputStream = new FileInputStream(fullFileName);
        }
        return new BufferedReader(new InputStreamReader(inputStream, "UTF-8"));
    }

}
