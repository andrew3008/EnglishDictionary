package com.englishDictionary.resourceReaders.soundDatFile;

import com.englishDictionary.config.Config;
import com.englishDictionary.config.EnvironmentType;
import com.englishDictionary.resourceReaders.resourceReader.SEDFileReader;
import com.englishDictionary.resourceReaders.resourceReader.SEDReader;
import com.englishDictionary.resourceReaders.resourceReader.SEDYandexDiskReader;
import com.englishDictionary.resourceReaders.soundDatFile.soundFileIndex.SoundFileIndexReader;
import com.englishDictionary.utils.LRUCache;

import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Created by Andrew on 7/10/2017.
 */
public class SoundDatFileReader /*extends RandomAccessFile*/ {

    private static int POSITION_NODES_CACHE_SIZE = 30;

    //private RandomAccessFile randomFile;
    private SEDReader randomFile;
    private SoundFileIndexReader soundEnInd;
    private LRUCache<String, SoundFileIndexReader.Node> positionNodesCache;
    private long curPosition = -1;
    private long endPosition = -1;
    private boolean eof = false;
    private long realBegPosition = -1;

    public SoundDatFileReader(String indFilePath, String datFilePath) throws IOException {
        //super(datFilePath, "r");
        soundEnInd = new SoundFileIndexReader();
        soundEnInd.readFromFile(indFilePath);
        //randomFile = new RandomAccessFile(datFilePath, "r");
        randomFile = (EnvironmentType.OPEN_SHIFT_CLUSTER == Config.INSTANCE.getEnvironmentType()) ? new SEDYandexDiskReader(datFilePath) : new SEDFileReader(datFilePath, "r");
        //randomFile = new SEDYandexDiskReader(datFilePath);
        positionNodesCache = new LRUCache(POSITION_NODES_CACHE_SIZE);
    }

    public boolean seekToFile(String fileName) {
        SoundFileIndexReader.Node filePosition = positionNodesCache.get(fileName);
        if (filePosition == null) {
            filePosition = soundEnInd.get(fileName);
            if (filePosition == null) {
                return false;
            }
            positionNodesCache.put(fileName, new SoundFileIndexReader.Node(filePosition));
        }

        try {
            randomFile.seek(filePosition.begPos);
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }

        curPosition = 0;
        endPosition = filePosition.endPos - filePosition.begPos;
        eof = false;
        realBegPosition = filePosition.begPos;
        return true;
    }

    //@Override
    public int read() throws IOException {
        if (eof) {
            return -1;
        }

        try {
            ++curPosition;
            eof = (curPosition > endPosition);
            return randomFile.read();
        } catch (IOException e) {
            e.printStackTrace();
            return -1;
        }
    }

    //@Override
    public int read(byte b[], int off, int len) throws IOException {
        if (len > (b.length - off)) {
            throw new IOException("[SoundEn] len > (b.length - off)");
        }

        long allowLen = (len <= (length() - curPosition)) ? len : length() - curPosition;
        if (allowLen == 0) {
            eof = true;
            return -1;
        }

        int rb = randomFile.read(b, off, (int)allowLen);
        if (rb == -1) {
            eof = true;
        } else {
            curPosition += rb;
            if (curPosition > endPosition) {
                eof = true;
            }
        }
        return rb;
    }

    //@Override
    public int read(byte b[]) throws IOException {
        return read(b, 0, b.length);
    }

    //@Override
    public long getFilePointer() throws IOException {
        return eof ? length() : curPosition;
    }

    //@Override
    public void seek(long pos) throws IOException {
        if (pos >= length()) {
            eof = true;
            return;
        } else if (pos < 0) {
            throw new IOException("[SoundEn.seek] pos < 0");
        }

        curPosition = pos;
        eof = false;
        randomFile.seek(realBegPosition + pos);
    }

    //@Override
    public long length() throws IOException {
        return endPosition + 1;
    }

    //@Override
    public void close() throws IOException {
    }

}
