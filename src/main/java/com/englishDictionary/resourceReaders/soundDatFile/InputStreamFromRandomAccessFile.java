package com.englishDictionary.resourceReaders.soundDatFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;

/**
 * Created by Andrew on 7/11/2017.
 */
public class InputStreamFromRandomAccessFile extends InputStream {

    private RandomAccessFile raFile;

    public InputStreamFromRandomAccessFile(RandomAccessFile raFile) {
        this.raFile = raFile;
    }

    @Override
    public int read() throws IOException {
        return raFile.read();
    }

    @Override
    public int read(byte b[], int off, int len) throws IOException {
        return raFile.read(b, off, len);
    }

    @Override
    public int read(byte b[]) throws IOException {
        return raFile.read(b);
    }

}
