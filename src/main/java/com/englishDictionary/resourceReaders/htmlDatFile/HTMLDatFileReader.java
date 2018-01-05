package com.englishDictionary.resourceReaders.htmlDatFile;

import com.englishDictionary.config.Config;
import com.englishDictionary.config.EnvironmentType;
import com.englishDictionary.utils.LRUCache;
import com.englishDictionary.utils.ResourceUtils;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.search.FuzzyQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.WildcardQuery;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

/**
 * Created by Andrew on 7/29/2017.
 */
class HTMLDatFileReader {

    private static int INDEX_CACHE_SIZE = 30;
    private static final int INIT_SIZE_BUFFER_IO = 8192;

    private class IndexNode {
        private int keyHashCode;
        private int htmlPos;
        private int htmlSize;
        private int index;
        private int left;
        private int right;

        private IndexNode() {
            keyHashCode = -1;
            htmlPos = -1;
            htmlSize = -1;
            index = -1;
            left = -1;
            right = -1;
        }
    }

    private SEDReader inStream;
    private IndexNode tempNode;
    int indexTreeSize;
    private int[] indexTree;
    private byte[] bufferIO;
    private LRUCache<String, Integer> indexCache;

    private String fileName;
    private IndexReader reader = null;

    public HTMLDatFileReader() {
        tempNode = new IndexNode();
        indexTreeSize = -1;
        bufferIO = new byte[INIT_SIZE_BUFFER_IO];
        indexCache = new LRUCache(INDEX_CACHE_SIZE);
    }

    public void open(String fileName) throws IOException {
        this.fileName = fileName;

        if (fileName.endsWith("Transcriptions.dat") || fileName.endsWith("Mnemonics.dat") || fileName.endsWith("IrregularVerbs.dat") || fileName.endsWith("WordCardHeaders.dat") ||
                EnvironmentType.OPEN_SHIFT_CLUSTER != Config.getEnvironmentType()) {
            inStream = new SEDFileReader(fileName, "r");
        } else {
            inStream = new SEDYandexDiskReader("EnglishDictionary_Resources/Dictionaries/DigitalDictionaries/" + ResourceUtils.getFileNameFromPath(fileName).replace(" ", "%20"));
        }

        // Size of header with tree index
        byte[] indexTreeSizeBytes = new byte[4];
        inStream.read(indexTreeSizeBytes, 0, 4);
        indexTreeSize = Bits.getInt(indexTreeSizeBytes, 0);

        // Index tree
        byte[] indexTreeBytes = new byte[indexTreeSize];
        inStream.read(indexTreeBytes);
        indexTree = new int[indexTreeSize / 4];
        for (int ind = 0; ind < indexTreeBytes.length; ind += 4) {
            indexTree[ind / 4] = Bits.getInt(indexTreeBytes, ind);
        }
    }

    public boolean existHTML(String word) {
        return getIndexNodeByKey(word) != null;
    }

    public String getHTML(String wordName) {
        IndexNode indexNode = getIndexNodeByKeyCached(wordName);
        if (indexNode == null) {
            return null;
        }

        String html = null;
        try {
            inStream.seek(indexNode.htmlPos);
            if (indexNode.htmlSize > INIT_SIZE_BUFFER_IO) {
                bufferIO = new byte[indexNode.htmlSize];
            }
            inStream.read(bufferIO, 0, indexNode.htmlSize);
            html = new String(bufferIO, 0, indexNode.htmlSize, StandardCharsets.UTF_8.name());
        } catch (IOException e) {
            e.printStackTrace();
        }
        return html;
    }

    public void readHTMLByWord(OutputStream outputStream, String word) {
        IndexNode indexNode = getIndexNodeByKeyCached(word);
        if (indexNode == null) {
            return;
        }

        try {
            inStream.seek(indexNode.htmlPos);
            int remainNumBytes = indexNode.htmlSize;
            if (remainNumBytes == 0) {
                return;
            }

            if (EnvironmentType.OPEN_SHIFT_CLUSTER == Config.getEnvironmentType()) {
                for (int nrBytes = 0; nrBytes != -1; nrBytes = inStream.read(outputStream, Integer.min(bufferIO.length, remainNumBytes))) {
                    if (nrBytes == 0) {
                        continue;
                    }

                    //outputStream.write(bufferIO, 0, nrBytes);
                    remainNumBytes -= nrBytes;
                    if (remainNumBytes == 0) {
                        return;
                    }
                }
            } else {
                // TODO: need to optimize without buffer
                for (int nrBytes = 0; nrBytes != -1; nrBytes = inStream.read(bufferIO, 0, Integer.min(bufferIO.length, remainNumBytes))) {
                    if (nrBytes == 0) {
                        continue;
                    }

                    outputStream.write(bufferIO, 0, nrBytes);
                    remainNumBytes -= nrBytes;
                    if (remainNumBytes == 0) {
                        return;
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private IndexNode getIndexNodeByKeyCached(String key) {
        Integer index = indexCache.get(key);
        if (index != null) {
            return getIndexNodeByIndex(tempNode, index.intValue());
        }

        IndexNode node = getIndexNodeByKey(key);
        if (node != null) {
            indexCache.put(key, node.index);
        }
        return node;
    }

    private IndexNode getIndexNodeByKey(String key) {
        int keyHashCode = key.hashCode();
        IndexNode node = getIndexNodeByIndex(tempNode, 0);
        while (node != null) {
            if (keyHashCode == node.keyHashCode) {
                return node;
            }

            if (keyHashCode < node.keyHashCode) {
                node = getIndexNodeByIndex(tempNode, node.left);
            } else {
                node = getIndexNodeByIndex(tempNode, node.right);
            }
        }
        return null;
    }

    private IndexNode getIndexNodeByIndex(IndexNode node, int index) {
        if ((index == -1) || (index >= indexTree.length)) {
            return null;
        }

        node.keyHashCode = indexTree[index];
        node.htmlPos = indexTree[index + 1];
        node.htmlSize = indexTree[index + 2];
        node.index = index;
        node.left = indexTree[index + 3];
        node.right = indexTree[index + 4];
        return node;
    }

    public List<String> searchLinkWords(String heardWord) {
        if (reader == null) {
            String dictionaryName = ResourceUtils.getFileNameWithoutExtnFromPath(fileName);
            Directory dir = null;
            try {
                dir = FSDirectory.open(Paths.get(Config.DIGITAL_DICTIONARIES_DIR + dictionaryName + "_Lucene_Index"));
            } catch (IOException e) {
                e.printStackTrace();
            }
            try {
                reader = DirectoryReader.open(dir);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        ScoreDoc[] scores = new ScoreDoc[0];
        try {
            //scores = fuzzySearch(heardWord, "word", 20, reader);
            scores = wildCardSearch(heardWord, "word", 40, reader);
        } catch (IOException e) {
            e.printStackTrace();
        } catch (ParseException e) {
            e.printStackTrace();
        }

        //showHits(scores, reader);

        if (scores.length == 0) {
            //System.out.println("\n\tНичего не найдено");
            //return Collections.EMPTY_SET;
            return Collections.EMPTY_LIST;
        }
        //System.out.println("\n\tРезультаты поиска:");

        //Set<String> linkWords = new LinkedHashSet<>();
        List<String> linkWords = new LinkedList<>();
        for (ScoreDoc score : scores) {
            try {
                final String word = reader.document(score.doc).get("word");
                linkWords.add(word);
            } catch (IOException e) {
                e.printStackTrace();
            }
            //System.out.println("\n\tDocument Id = " + hit.doc + "\n\tword = " + word);
        }
        return linkWords;
    }

    public void closeFile() throws IOException {
        inStream.close();
        //dir.close();
    }

    // ----------------------------------------------------------------------------------------------------

    /**
     * Search using FuzzyQuery.
     *
     * @param toSearch    string to search
     * @param searchField field where to search. We have "body" and "title" fields
     * @param limit       how many results to return
     * @throws IOException
     * @throws ParseException
     */
    public static ScoreDoc[] fuzzySearch(final String toSearch, final String searchField, final int limit, IndexReader reader) throws IOException, ParseException {
        final IndexSearcher indexSearcher = new IndexSearcher(reader);

        final Term term = new Term(searchField, toSearch);

        final int maxEdits = 2; // This is very important variable. It regulates fuzziness of the query
        final Query query = new FuzzyQuery(term, maxEdits);
        final TopDocs search = indexSearcher.search(query, limit);
        final ScoreDoc[] hits = search.scoreDocs;
        return hits;
    }

    public static ScoreDoc[] wildCardSearch(final String toSearch, final String searchField, final int limit, IndexReader reader) throws IOException, ParseException {
//        //Get directory reference
//        Directory dir = FSDirectory.open(Paths.get(INDEX_DIR));
//
//        //Index reader - an interface for accessing a point-in-time view of a lucene index
//        IndexReader reader = DirectoryReader.open(dir);
//
//        //Create lucene searcher. It search over a single IndexReader.
//        IndexSearcher searcher = new IndexSearcher(reader);
//
//        //analyzer with the default stop words
//        Analyzer analyzer = new StandardAnalyzer();

        final IndexSearcher indexSearcher = new IndexSearcher(reader);

        //final Term term = new Term(searchField, toSearch + "*");
        final Term term = new Term(searchField, toSearch);

        //Create wildcard query
        Query query = new WildcardQuery(term);

        //Search the lucene documents
        //TopDocs hits = indexSearcher.search(query, limit, Sort.INDEXORDER);
        TopDocs hits = indexSearcher.search(query, limit, Sort.RELEVANCE);
        return hits.scoreDocs;
    }

}
