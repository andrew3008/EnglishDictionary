package com.englishDictionary.resourceReaders.soundDatFile.soundFileIndex;

import com.englishDictionary.config.Config;
import com.englishDictionary.config.EnvironmentType;
import com.englishDictionary.resourceReaders.resourceReader.SEDFileReader;
import com.englishDictionary.resourceReaders.resourceReader.SEDReader;
import com.englishDictionary.resourceReaders.resourceReader.SEDYandexDiskReader;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * Created by Andrew on 7/15/2017.
 */
public class SoundFileIndexReader {

    public static class Node {
        private int keyHashCode;
        public long begPos;
        public long endPos;
        private int left;
        private int right;

        private Node() {
            keyHashCode = -1;
            begPos = -1;
            endPos = -1;
            left = -1;
            right = -1;
        }

        public Node(Node node) {
            keyHashCode = node.keyHashCode;
            begPos = node.begPos;
            endPos = node.endPos;
            left = node.left;
            right = node.right;
        }

        @Override
        public String toString() {
            return "[node] keyHashCode:" + keyHashCode + ", begPos:" + begPos + ", endPos:" + endPos + ", left:" + left + ", right:" + right;
        }

    }

    private long[] arrayNode;
    private Node tempNode;

    public SoundFileIndexReader() {
        tempNode = new Node();
    }

    public void readFromFile(String fileName) throws IOException {
        SEDReader reader = (EnvironmentType.OPEN_SHIFT_CLUSTER == Config.INSTANCE.getEnvironmentType()) ? new SEDYandexDiskReader(fileName) : new SEDFileReader(fileName, "r");
//        SEDReader reader = new SEDYandexDiskReader(fileName);
        byte[] bytes = new byte[(int)reader.fileLength()];
        reader.read(bytes);
        reader.close();

        arrayNode = new long[bytes.length / 8];
        for (int ind = 0; ind < bytes.length; ind += 8) {
            arrayNode[ind / 8] = Bits.getLong(bytes, ind);
        }
    }

    public Node get(String key) {
        int keyHashCode = key.hashCode();
        Node node = getNodeByIndex(tempNode, 0);
        while (node != null) {
            if (keyHashCode == node.keyHashCode) {
                return node;
            }

            if (keyHashCode < node.keyHashCode) {
                node = getNodeByIndex(tempNode, node.left);
            } else {
                node = getNodeByIndex(tempNode, node.right);
            }
        }
        return null;
    }

    private Node getNodeByIndex(Node node, int index)  {
        if ((index == -1) || (index >= arrayNode.length)) {
            return null;
        }

        node.keyHashCode = (int)arrayNode[index];
        node.begPos = arrayNode[index + 1];
        node.endPos = arrayNode[index + 2];
        node.left = (int)arrayNode[index + 3];
        node.right = (int)arrayNode[index + 4];
        return node;
    }

}
