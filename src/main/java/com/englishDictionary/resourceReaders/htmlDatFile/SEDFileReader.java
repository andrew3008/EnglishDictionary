package com.englishDictionary.resourceReaders.htmlDatFile;

import com.englishDictionary.webServer.ByteArrayOutputStream;

import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;

public class SEDFileReader implements SEDReader {

    private RandomAccessFile file;

    public SEDFileReader(String fileName, String accessMode) throws IOException {
        file = new RandomAccessFile(fileName, accessMode);
    }

    @Override
    public int read(byte[] b) throws IOException {
        return file.read(b);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        return file.read(b, off, len);
    }

    @Override
    public int read(OutputStream outputStream, int len) throws IOException {
        /*ByteArrayOutputStream outputStreamBA = (ByteArrayOutputStream)outputStream;
        file.read(outputStreamBA.toByteArray(), outputStreamBA.size(), len);*/
        return -1;
    }

    @Override
    public void seek(long position) throws IOException {
        file.seek(position);
    }

    @Override
    public void close() throws IOException {
        file.close();
    }

}
