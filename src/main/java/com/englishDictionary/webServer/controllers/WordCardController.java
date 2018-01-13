package com.englishDictionary.webServer.controllers;

import com.englishDictionary.config.Config;
import com.englishDictionary.config.openShiftClaster.ConfigOpenShiftCluster;
import com.englishDictionary.resourceReaders.htmlDatFile.HTMLFragmentReader;
import com.englishDictionary.resourceReaders.soundDatFile.InputStreamFromRandomAccessFile;
import com.englishDictionary.resourceReaders.soundDatFile.MP3Player;
import com.englishDictionary.resourceReaders.soundDatFile.SoundDatFileReader;
import com.englishDictionary.utils.SplitterPhraseToWords;
import com.englishDictionary.utils.httl.HttlEngineKeeper;
import com.englishDictionary.webServer.ByteArrayOutputStream;
import com.englishDictionary.webServer.HttpServletRequest;
import com.englishDictionary.webServer.HttpServletResponse;
import com.englishDictionary.webServer.annotations.Controller;
import com.englishDictionary.webServer.annotations.RequestMapping;
import io.netty.handler.codec.http.HttpResponseStatus;

import java.io.IOException;
import java.net.URISyntaxException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Controller(url = "/wordCard/")
public class WordCardController {

    private static List<HTMLFragmentReader> enRuDictionaries = new ArrayList<>();
    private boolean isOpenEnRuDictionaries = false;
    private HTMLFragmentReader wordCardHeaders;
    private HTMLFragmentReader irregularVerbs;

    private SoundDatFileReader oald9SoundEn = null;
    private SoundDatFileReader ldoce6SoundEn = null;
    private InputStreamFromRandomAccessFile oald9SoundEnStream = null;
    private InputStreamFromRandomAccessFile ldoce6SoundEnStream = null;

    @RequestMapping(url = "loadFromEnRuDictionaries.html")
    public void getWordCardFromEnRuDictionaries(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String phrase = request.getParameter("word");
        if (!isOpenEnRuDictionaries) {
            List<String> dictFileNames = new ArrayList<>();
            dictFileNames.add("Lingvo Universal.dat");

            for (String fileName : dictFileNames) {
                HTMLFragmentReader dictionary = new HTMLFragmentReader(Config.INSTANCE.getDigitalDictionariesDir() + fileName);
                enRuDictionaries.add(dictionary);
            }

            irregularVerbs = new HTMLFragmentReader(Config.INSTANCE.getIrregularVerbsFilePath());
            wordCardHeaders = new HTMLFragmentReader(Config.INSTANCE.getMED2WordCardHeadersFilePath());
            isOpenEnRuDictionaries = true;
        }

        try {
            List<String> headWords = SplitterPhraseToWords.splitPhrase(phrase);
            ByteArrayOutputStream outputStream = response.getOutputStream();
            Map<String, Object> viewParameters = new HashMap<>();
            viewParameters.put("headWords", headWords);
            HttlEngineKeeper.engine.getTemplate("wordCardPageHeader.httl").render(viewParameters, outputStream);

            // TODO: Find without -s, -ed if not found
            for (String heardWord : headWords) {
                if (headWords.size() == 1) {
                    outputStream.write("<div class=\"tab-pane fade active in\" id = \"tab-pane-heardWord-" + heardWord + "\">");
                } else {
                    outputStream.write("<div class=\"tab-pane fade\" id = \"tab-pane-heardWord-" + heardWord + "\">");
                }

                // Show header of word card
                if (wordCardHeaders.existHTMLByWord(heardWord)) {
                    outputStream.write("<div style=\"margin-top: -12px;\"></div>");
                    wordCardHeaders.readHTMLByWord(outputStream, heardWord);
                    outputStream.write("<div style=\"margin-bottom: 12px;\"></div>");
                }

                // Show panel for irregular verb
                if (irregularVerbs.existHTMLByWord(heardWord)) {
                    irregularVerbs.readHTMLByWord(outputStream, heardWord);
                }

                boolean wordFoundInSomeDictionary = false;
                boolean isFirstArticle = true;
                for (HTMLFragmentReader dictionary : enRuDictionaries) {
                    if (dictionary.existHTMLByWord(heardWord)) {
                        wordFoundInSomeDictionary = true;
                        if (isFirstArticle) {
                            isFirstArticle = false;
                        } else {
                            outputStream.write("<br>");
                        }

                        outputStream.write("<div class=\"gdarticle\">");
                        outputStream.write("    <div class=\"gddictname\">");
                        outputStream.write("        <span class=\"gddicttitle\">" + dictionary.getFileName() + "</span>");
                        outputStream.write("    </div>");
                        outputStream.write("    <div class=\"gddictnamebodyseparator\"></div>");
                        outputStream.write("    <span class=\"gdarticlebody\" style=\"display:inline\">");
                        outputStream.write("        <span class=\"dsl_article\">");
                        dictionary.readHTMLByWord(response.getOutputStream(), heardWord);
                        outputStream.write("        </span>");
                        outputStream.write("    </span>");
                        outputStream.write("</div>");
                    }
                }

                if (!wordFoundInSomeDictionary) {
                    outputStream.write("<span>The word didn't find in any dictionary.</span>");
                }

                outputStream.write("</div>");
            }

            HttlEngineKeeper.engine.getTemplate("wordCardPageFooter.httl").render(viewParameters, outputStream);
        } catch (ParseException | IOException e) {
            e.printStackTrace();
            response.setStatus(HttpResponseStatus.INTERNAL_SERVER_ERROR);
            response.setErrorMessage(e.getMessage());
        }
    }


    // ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------

    private static List<HTMLFragmentReader> slangDictionaries = new ArrayList<>();
    private boolean isOpenSlangDictionaries = false;

    @RequestMapping(url = "loadFromLDOCE6.html")
    public void getWordCardFromLDOCE6(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String phrase = request.getParameter("word");
        boolean processWholeWord = "true".equals(request.getParameter("wholeWord"));
        boolean needToShowKeyWords = "true".equals(request.getParameter("needToShowKeyWords"));

        if (!isOpenSlangDictionaries) {
            List<String> slangDictionaryFileNames = new ArrayList<>();
            slangDictionaryFileNames.add("LDOCE6.dat");

            for (String fileName : slangDictionaryFileNames) {
                HTMLFragmentReader dictionary = new HTMLFragmentReader(Config.INSTANCE.getDigitalDictionariesDir() + fileName);
                slangDictionaries.add(dictionary);
            }

            isOpenSlangDictionaries = true;
        }

        try {
            ByteArrayOutputStream outputStream = response.getOutputStream();
            Map<String, Object> viewParameters = new HashMap<>();
            List<String> headWords = processWholeWord ? Collections.singletonList(phrase) : SplitterPhraseToWords.splitPhrase(phrase);
            viewParameters.put("headWords", headWords);
            HttlEngineKeeper.engine.getTemplate("wordCardPageHeader.httl").render(viewParameters, outputStream);

            // TODO: Find without -s, -ed if not found
            for (String heardWord : headWords) {
                if (headWords.size() == 1) {
                    outputStream.write("<div class=\"tab-pane fade active in\" id = \"tab-pane-heardWord-" + heardWord + "\">");
                } else {
                    outputStream.write("<div class=\"tab-pane fade\" id = \"tab-pane-heardWord-" + heardWord + "\">");
                }

                boolean wordFoundInSomeDictionary = false;
                boolean isFirstArticle = true;
                for (HTMLFragmentReader dictionary : slangDictionaries) {
                    if (dictionary.existHTMLByWord(heardWord)) {
                        wordFoundInSomeDictionary = true;
                        if (isFirstArticle) {
                            isFirstArticle = false;
                        } else {
                            outputStream.write("<br>");
                        }

                        outputStream.write("<div class=\"gdarticle\">");
                        outputStream.write("    <div class=\"gddictname\">");
                        outputStream.write("       <span class=\"gddicttitle\">" + dictionary.getFileName() + "</span>");
                        outputStream.write("    </div>");

                        outputStream.write("    <div class=\"gddictnamebodyseparator\"></div>");
                        outputStream.write("        <span class=\"gdarticlebody\" lang=\"en\" style=\"display:inline\">");

                        // Колонка со стаьёй
                        if (needToShowKeyWords) {
                            outputStream.write("           <div class=\"ldoce6 two_column_layout_left_column\">");
                        } else {
                            outputStream.write("           <div class=\"ldoce6\">");
                        }
                        dictionary.readHTMLByWord(response.getOutputStream(), heardWord);
                        outputStream.write("               </div>");

                        // Link Words
                        if (needToShowKeyWords) {
                            outputStream.write("           <div class=\"dictionary_search_results two_column_layout_right_column\">");
                            outputStream.write("                <h1 class=\"search_title\">");
                            outputStream.write("                    Link words");
                            outputStream.write("                </h1>");
                            outputStream.write("                <ul class=\"searches\">");
                            for (String resultWord : dictionary.searchLinkWords(heardWord)) {
                                outputStream.write("                <li>");
                                outputStream.write("                   <a onclick=\"goToWord('mainLDOCE6', '" + resultWord + "')\"><span class=\"arl1\">" + resultWord + "</span></a>");
                                outputStream.write("                </li>");
                            }
                            outputStream.write("                </ul>");
                            outputStream.write("           </div>");
                        }

                        outputStream.write("        </span>");
                        outputStream.write("   </div>");
                    }
                }

                if (!wordFoundInSomeDictionary) {
                    outputStream.write("<span>The word didn't find in any dictionary.</span>");
                }

                outputStream.write("</div>");
            }

            HttlEngineKeeper.engine.getTemplate("wordCardPageFooter.httl").render(viewParameters, outputStream);
        } catch (ParseException | IOException e) {
            e.printStackTrace();
            response.setStatus(HttpResponseStatus.INTERNAL_SERVER_ERROR);
            response.setErrorMessage(e.getMessage());
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------

    private static List<HTMLFragmentReader> adla2Dictionaries = new ArrayList<>();
    private boolean isOpenADLA2Dictionaries = false;

    @RequestMapping(url = "loadFromADLA2.html")
    public void getWordCardFromADLA2(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String phrase = request.getParameter("word");
        boolean processWholeWord = "true".equals(request.getParameter("wholeWord"));

        if (!isOpenADLA2Dictionaries) {
            List<String> slangDictionaryFileNames = new ArrayList<>();
            slangDictionaryFileNames.add("ADLA2.dat");

            for (String fileName : slangDictionaryFileNames) {
                HTMLFragmentReader dictionary = new HTMLFragmentReader(Config.INSTANCE.getDigitalDictionariesDir() + fileName);
                adla2Dictionaries.add(dictionary);
            }

            isOpenADLA2Dictionaries = true;
        }

        try {
            ByteArrayOutputStream outputStream = response.getOutputStream();
            Map<String, Object> viewParameters = new HashMap<>();
            List<String> headWords = processWholeWord ? Collections.singletonList(phrase) : SplitterPhraseToWords.splitPhrase(phrase);
            viewParameters.put("headWords", headWords);
            HttlEngineKeeper.engine.getTemplate("wordCardPageHeader.httl").render(viewParameters, outputStream);

            // TODO: Find without -s, -ed if not found
            for (String heardWord : headWords) {
                if (headWords.size() == 1) {
                    outputStream.write("<div class=\"tab-pane fade active in\" id = \"tab-pane-heardWord-" + heardWord + "\">");
                } else {
                    outputStream.write("<div class=\"tab-pane fade\" id = \"tab-pane-heardWord-" + heardWord + "\">");
                }

                boolean wordFoundInSomeDictionary = false;
                boolean isFirstArticle = true;
                for (HTMLFragmentReader dictionary : adla2Dictionaries) {
                    if (dictionary.existHTMLByWord(heardWord)) {
                        wordFoundInSomeDictionary = true;
                        if (isFirstArticle) {
                            isFirstArticle = false;
                        } else {
                            outputStream.write("<br>");
                        }

                        outputStream.write("<div class=\"gdarticle\">");
                        outputStream.write("    <div class=\"gddictname\">");
                        outputStream.write("       <span class=\"gddicttitle\">" + dictionary.getFileName() + "</span>");
                        outputStream.write("    </div>");

                        outputStream.write("    <div class=\"gddictnamebodyseparator\"></div>");
                        outputStream.write("        <span class=\"gdarticlebody\" lang=\"en\" style=\"display:inline\">");
                        outputStream.write("                <div class=\"adla2\">");
                        dictionary.readHTMLByWord(response.getOutputStream(), heardWord);
                        outputStream.write("                </div>");
                        outputStream.write("        </span>");
                        outputStream.write("    </div>");

                        outputStream.write("</div>");
                    }
                }

                if (!wordFoundInSomeDictionary) {
                    for (HTMLFragmentReader dictionary : adla2Dictionaries) {
                        outputStream.write("<div class=\"gdarticle\">");
                        outputStream.write("    <div class=\"gddictname\">");
                        outputStream.write("       <span class=\"gddicttitle\">" + dictionary.getFileName() + "</span>");
                        outputStream.write("    </div>");
                        outputStream.write("    <span>The word wasn't found in this dictionary.</span>");
                        outputStream.write("</div>");
                    }
                }

                outputStream.write("</div>");
            }

            HttlEngineKeeper.engine.getTemplate("wordCardPageFooter.httl").render(viewParameters, outputStream);
        } catch (ParseException | IOException e) {
            e.printStackTrace();
            response.setStatus(HttpResponseStatus.INTERNAL_SERVER_ERROR);
            response.setErrorMessage(e.getMessage());
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------

    //Example pagination for Link Words
    //http://www.ldoceonline.com/search/?q=sun

    private static List<HTMLFragmentReader> OALD9Dictionaries = new ArrayList<>();
    private boolean isOpenOALD9Dictionaries = false;

    @RequestMapping(url = "loadFromOALD9.html")
    public void getWordCardFromOALD9(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String phrase = request.getParameter("word");
        boolean processWholeWord = "true".equals(request.getParameter("wholeWord"));
        boolean needToShowKeyWords = "true".equals(request.getParameter("needToShowKeyWords"));

        if (!isOpenOALD9Dictionaries) {
            List<String> collocationDictionaryFileNames = new ArrayList<>();
            collocationDictionaryFileNames.add("OALD 9.dat");

            for (String fileName : collocationDictionaryFileNames) {
                HTMLFragmentReader dictionary = new HTMLFragmentReader(Config.INSTANCE.getDigitalDictionariesDir() + fileName);
                OALD9Dictionaries.add(dictionary);
            }

            isOpenOALD9Dictionaries = true;
        }

        try {
            ByteArrayOutputStream outputStream = response.getOutputStream();
            Map<String, Object> viewParameters = new HashMap<>();
            List<String> headWords = processWholeWord ? Collections.singletonList(phrase) : SplitterPhraseToWords.splitPhrase(phrase);
            viewParameters.put("headWords", headWords);
            HttlEngineKeeper.engine.getTemplate("wordCardPageHeader.httl").render(viewParameters, outputStream);

            // TODO: Find without -s, -ed if not found
            for (String heardWord : headWords) {
                if (headWords.size() == 1) {
                    outputStream.write("<div class=\"tab-pane fade active in\" id = \"tab-pane-heardWord-" + heardWord + "\">");
                } else {
                    outputStream.write("<div class=\"tab-pane fade\" id = \"tab-pane-heardWord-" + heardWord + "\">");
                }

                boolean wordFoundInSomeDictionary = false;
                boolean isFirstArticle = true;
                for (HTMLFragmentReader dictionary : OALD9Dictionaries) {
                    if (dictionary.existHTMLByWord(heardWord)) {
                        wordFoundInSomeDictionary = true;
                        if (isFirstArticle) {
                            isFirstArticle = false;
                        } else {
                            outputStream.write("<br>");
                        }

                        outputStream.write("<div class=\"gdarticle\">");
                        outputStream.write("    <div class=\"gddictname\">");
                        outputStream.write("       <span class=\"gddicttitle\">" + dictionary.getFileName() + "</span>");
                        outputStream.write("    </div>");

                        outputStream.write("    <div class=\"gddictnamebodyseparator\"></div>");
                        outputStream.write("        <span class=\"gdarticlebody\" lang=\"en\" style=\"display:inline\">");

                        // Колонка со стаьёй
                        if (needToShowKeyWords) {
                            outputStream.write("    <div class=\"mdict two_column_layout_left_column\">");
                        } else {
                            outputStream.write("         <div class=\"mdict\">");
                        }

                        dictionary.readHTMLByWord(response.getOutputStream(), heardWord);
                        outputStream.write("             </div>");

                        // Link Words
                        if (needToShowKeyWords) {
                            outputStream.write("         <div class=\"dictionary_search_results two_column_layout_right_column\">");
                            outputStream.write("              <h1 class=\"search_title\">");
                            outputStream.write("                  Link words");
                            outputStream.write("              </h1>");
                            outputStream.write("              <ul class=\"searches\">");
                            for (String resultWord : dictionary.searchLinkWords(heardWord)) {
                                outputStream.write("              <li>");
                                outputStream.write("                <a onclick=\"goToWord('mainOALD9', '" + resultWord + "')\"><span class=\"arl1\">" + resultWord + "</span></a>");
                                outputStream.write("              </li>");
                            }
                            outputStream.write("              </ul>");
                            outputStream.write("         </div>");
                        }

                        outputStream.write("      </span>");
                        outputStream.write("  </div>");
                    }
                }

                if (!wordFoundInSomeDictionary) {
                    outputStream.write("<span>The word didn't find in any dictionary.</span>");
                }

                outputStream.write("</div>");
            }

            HttlEngineKeeper.engine.getTemplate("wordCardPageFooter.httl").render(viewParameters, outputStream);
        } catch (ParseException | IOException e) {
            e.printStackTrace();
            response.setStatus(HttpResponseStatus.INTERNAL_SERVER_ERROR);
            response.setErrorMessage(e.getMessage());
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------

    private static List<HTMLFragmentReader> collocationDictionaries = new ArrayList<>();
    private boolean isOpenCollocationDictionaries = false;

    @RequestMapping(url = "loadFromCollocations.html")
    public void getWordCardFromCollocations(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!isOpenCollocationDictionaries) {
            List<String> collocationDictionaryFileNames = new ArrayList<>();
            collocationDictionaryFileNames.add("Oxford_Collocations.dat");
            collocationDictionaryFileNames.add("LDOCE5_Extras.dat");

            for (String fileName : collocationDictionaryFileNames) {
                HTMLFragmentReader dictionary = new HTMLFragmentReader(Config.INSTANCE.getDigitalDictionariesDir() + fileName);
                collocationDictionaries.add(dictionary);
            }

            isOpenCollocationDictionaries = true;
        }

        try {
            ByteArrayOutputStream outputStream = response.getOutputStream();
            Map<String, Object> viewParameters = new HashMap<>();
            List<String> headWords = SplitterPhraseToWords.splitPhrase(request.getParameter("word"));
            viewParameters.put("headWords", headWords);
            HttlEngineKeeper.engine.getTemplate("wordCardPageHeader.httl").render(viewParameters, outputStream);

            // TODO: Find without -s, -ed if not found
            for (String heardWord : headWords) {
                if (headWords.size() == 1) {
                    outputStream.write("<div class=\"tab-pane fade active in\" id = \"tab-pane-heardWord-" + heardWord + "\">");
                } else {
                    outputStream.write("<div class=\"tab-pane fade\" id = \"tab-pane-heardWord-" + heardWord + "\">");
                }

                outputStream.write("        <div style=\"width: 100%; padding: 1px;\">");
                outputStream.write("            <table style=\"height: 1015px; width: 100%;\">");
                outputStream.write("                <tr>");
                outputStream.write("                    <td style=\"vertical-align: top; width: 50%; padding-right: 5px;\">");
                createArticlePanel(collocationDictionaries.get(0), DictionaryType.DSL, heardWord, outputStream);
                outputStream.write("                    </td>");
                outputStream.write("                    <td style=\"vertical-align: top; width: 50%; padding-left: 5px;\">");
                createArticlePanel(collocationDictionaries.get(1), DictionaryType.DSL, heardWord, outputStream);
                outputStream.write("                    </td>");
                outputStream.write("                </tr>");
                outputStream.write("            </table>");
                outputStream.write("        </div>");

                outputStream.write("</div>");
            }

            HttlEngineKeeper.engine.getTemplate("wordCardPageFooter.httl").render(viewParameters, outputStream);
        } catch (ParseException | IOException e) {
            e.printStackTrace();
            response.setStatus(HttpResponseStatus.INTERNAL_SERVER_ERROR);
            response.setErrorMessage(e.getMessage());
        }
    }

    private void createArticlePanel(HTMLFragmentReader dictionary, DictionaryType dictionaryType, String heardWord, ByteArrayOutputStream outputStream) throws IOException {
        outputStream.write("<div class=\"gdarticle\">");
        outputStream.write("    <div class=\"gddictname\">");
        outputStream.write("        <span class=\"gddicttitle\">" + dictionary.getFileName() + "</span>");
        outputStream.write("    </div>");
        outputStream.write("    <div class=\"gddictnamebodyseparator\\\">");
        outputStream.write("        <span class=\"gdarticlebody\" style=\"display:inline\">");

        if (dictionary.existHTMLByWord(heardWord)) {
            if (DictionaryType.DSL.equals(dictionaryType)) {
                outputStream.write("        <span class=\"dsl_article\">");
            } else if (DictionaryType.MDX.equals(dictionaryType)) {
                outputStream.write("        <span class=\"mdict\">");
            } else if (DictionaryType.DICT.equals(dictionaryType)) {
                outputStream.write("        <span class=\"sdct_x\">");
            }
            dictionary.readHTMLByWord(outputStream, heardWord);
            outputStream.write("            </span>");
        } else {
            outputStream.write("<span>The word didn't find in any dictionary.</span>");
        }

        outputStream.write("        </span>");
        outputStream.write("    </div>");
        outputStream.write("</div>");
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------

    private static Map<HTMLFragmentReader, DictionaryType> thesaurusDictionaries = new HashMap<>();
    private boolean isOpenThesaurusDictionaries = false;

    @RequestMapping(url = "loadFromThesaurus.html")
    public void getWordCardFromThesaurus(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String phrase = request.getParameter("word");
        boolean processWholeWord = "true".equals(request.getParameter("wholeWord"));
        boolean needToShowKeyWords = "true".equals(request.getParameter("needToShowKeyWords"));

        if (!isOpenThesaurusDictionaries) {
            Map<String, DictionaryType> thesaurusDictionaryFileNames = new HashMap<>();
            thesaurusDictionaryFileNames.put("Logman_Common_Errors.dat", DictionaryType.DICT);
            thesaurusDictionaryFileNames.put("Collins_Thesaurus.dat", DictionaryType.DSL);
            thesaurusDictionaryFileNames.put("eng_eng_errors_di_1_0.dat", DictionaryType.DSL);

            for (Map.Entry<String, DictionaryType> fileEntry : thesaurusDictionaryFileNames.entrySet()) {
                HTMLFragmentReader dictionary = new HTMLFragmentReader(Config.INSTANCE.getDigitalDictionariesDir() + fileEntry.getKey());
                thesaurusDictionaries.put(dictionary, fileEntry.getValue());
            }

            isOpenThesaurusDictionaries = true;
        }

        try {
            ByteArrayOutputStream outputStream = response.getOutputStream();
            Map<String, Object> viewParameters = new HashMap<>();
            List<String> headWords = processWholeWord ? Collections.singletonList(phrase) : SplitterPhraseToWords.splitPhrase(phrase);
            viewParameters.put("headWords", headWords);
            HttlEngineKeeper.engine.getTemplate("wordCardPageHeader.httl").render(viewParameters, outputStream);

            // TODO: Find without -s, -ed if not found
            for (String heardWord : headWords) {
                if (headWords.size() == 1) {
                    outputStream.write("<div class=\"tab-pane fade active in\" id = \"tab-pane-heardWord-" + heardWord + "\">");
                } else {
                    outputStream.write("<div class=\"tab-pane fade\" id = \"tab-pane-heardWord-" + heardWord + "\">");
                }

                boolean wordFoundInSomeDictionary = false;
                boolean isFirstArticle = true;
                for (Map.Entry<HTMLFragmentReader, DictionaryType> dictionaryEntry : thesaurusDictionaries.entrySet()) {
                    if (dictionaryEntry.getKey().existHTMLByWord(heardWord)) {
                        wordFoundInSomeDictionary = true;
                        if (isFirstArticle) {
                            isFirstArticle = false;
                        } else {
                            outputStream.write("<br>");
                        }

                        outputStream.write("<div class=\"gdarticle\">");
                        outputStream.write("    <div class=\"gddictname\">");
                        outputStream.write("        <span class=\"gddicttitle\">" + dictionaryEntry.getKey().getFileName() + "</span>");
                        outputStream.write("    </div>");
                        outputStream.write("    <div class=\"gddictnamebodyseparator\\\">");
                        outputStream.write("        <span class=\"gdarticlebody\" style=\"display:inline\">");
                        if (DictionaryType.DSL.equals(dictionaryEntry.getValue())) {
                            outputStream.write("        <span class=\"dsl_article" + (needToShowKeyWords ? " two_column_layout_left_column" : "") + "\">");
                        } else if (DictionaryType.DICT.equals(dictionaryEntry.getValue())) {
                            outputStream.write("        <span class=\"sdct_x" + (needToShowKeyWords ? " two_column_layout_left_column" : "") + "\">");
                        }
                        dictionaryEntry.getKey().readHTMLByWord(response.getOutputStream(), heardWord);
                        // Link Words
                        if (needToShowKeyWords) {
                            outputStream.write("         <div class=\"dictionary_search_results two_column_layout_right_column\">");
                            outputStream.write("              <h1 class=\"search_title\">");
                            outputStream.write("                  Link words");
                            outputStream.write("              </h1>");
                            outputStream.write("              <ul class=\"searches\">");
                            for (String resultWord : dictionaryEntry.getKey().searchLinkWords(heardWord)) {
                                outputStream.write("              <li>");
                                outputStream.write("                <a onclick=\"goToWord('mainThesaurus', '" + resultWord + "')\"><span class=\"arl1\">" + resultWord + "</span></a>");
                                outputStream.write("              </li>");
                            }
                            outputStream.write("              </ul>");
                            outputStream.write("         </div>");
                        }
                        outputStream.write("          </span>");
                        outputStream.write("     </span>");
                        outputStream.write("   </div>");
                        outputStream.write("</div>");
                    }
                }

                if (!wordFoundInSomeDictionary) {
                    outputStream.write("<span>The word didn't find in any dictionary.</span>");
                }

                outputStream.write("</div>");
            }

            HttlEngineKeeper.engine.getTemplate("wordCardPageFooter.httl").render(viewParameters, outputStream);
        } catch (ParseException | IOException e) {
            e.printStackTrace();
            response.setStatus(HttpResponseStatus.INTERNAL_SERVER_ERROR);
            response.setErrorMessage(e.getMessage());
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------

    @RequestMapping(url = "playLingvoSoundFile.html")
    public void playLingvoSoundFile(HttpServletRequest request) {
        /*try {
            if (oald9SoundEn == null) {
                oald9SoundEn = new SoundDatFileReader(Config.OALD9_SOUND_IND_FILE, Config.OALD9_SOUND_DAT_FILE);
                oald9SoundEnStream = new InputStreamFromRandomAccessFile(oald9SoundEn);
            }

            if (oald9SoundEn.seekToFile(request.getParameter("fileName"))) {
                MP3Player.play(oald9SoundEnStream);
            }
        } catch (IOException | URISyntaxException ex) {
            ex.printStackTrace();
        }*/
    }

    private static int BUFFER_SIZE = 6144;
    private byte[] buffer = new byte[BUFFER_SIZE];

    @RequestMapping(url = "playOALD9SoundFile.html")
    public void playOALD9SoundFile(HttpServletRequest request, HttpServletResponse response) {
        try {
            if (oald9SoundEn == null) {
                oald9SoundEn = new SoundDatFileReader(Config.INSTANCE.getOALD9SoundIndFilePath(), Config.INSTANCE.getOALD9SoundDatFilePath());
                /*oald9SoundEn = new SoundDatFileReader("EnglishDictionary_Resources/Dictionaries/OALD9/Sounds/SoundEn.ind",
                        "EnglishDictionary_Resources/Dictionaries/OALD9/Sounds/SoundEn.dat");*/
                oald9SoundEnStream = new InputStreamFromRandomAccessFile(oald9SoundEn);
            }

            if (oald9SoundEn.seekToFile(request.getParameter("fileName"))) {
                //MP3Player.play(oald9SoundEnStream);
                int lengthReadData;
                while ((lengthReadData = oald9SoundEnStream.read(buffer, 0, buffer.length)) != -1) {
                    response.getOutputStream().write(buffer, 0, lengthReadData);
                }
            }
        } catch (IOException /*| URISyntaxException*/ ex) {
            ex.printStackTrace();
        }
    }

    @RequestMapping(url = "playLDOCE6SoundFile.html")
    public void playLDOCE6SoundFile(HttpServletRequest request, HttpServletResponse response) {
        try {
            if (ldoce6SoundEn == null) {
                ldoce6SoundEn = new SoundDatFileReader(Config.INSTANCE.getLDOCE6SoundIndFileParh(), Config.INSTANCE.getLDOCE6SoundDatFilePath());
                ldoce6SoundEnStream = new InputStreamFromRandomAccessFile(ldoce6SoundEn);
            }

            if (ldoce6SoundEn.seekToFile(request.getParameter("fileName"))) {
                //MP3Player.play(ldoce6SoundEnStream);
                int lengthReadData;
                while ((lengthReadData = ldoce6SoundEnStream.read(buffer, 0, buffer.length)) != -1) {
                    response.getOutputStream().write(buffer, 0, lengthReadData);
                }
            }
        } catch (IOException /*| URISyntaxException*/ ex) {
            ex.printStackTrace();
        }
    }

}
