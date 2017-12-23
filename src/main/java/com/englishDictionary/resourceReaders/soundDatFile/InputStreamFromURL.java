package com.englishDictionary.resourceReaders.soundDatFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;

public class InputStreamFromURL extends InputStream {
    private InputStream inputStream;

    public InputStreamFromURL(String streamURL) {
        super();
        try {
            inputStream = new URL(streamURL).openConnection().getInputStream();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public int read() throws IOException {
        return inputStream.read();
    }

    public int read(byte[] b, int off, int len) throws IOException {
        return inputStream.read(b, off, len);
    }

    public synchronized void mark(int readlimit) {}

    public synchronized void reset() throws IOException {}

    public void close() throws IOException {
        inputStream.close();
    }
}
