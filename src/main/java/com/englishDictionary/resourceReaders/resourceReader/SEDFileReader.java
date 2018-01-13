package com.englishDictionary.resourceReaders.resourceReader;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;

public class SEDFileReader implements SEDReader {

    private String filePath;
    private RandomAccessFile file;

    public SEDFileReader(String filePath, String accessMode) throws IOException {
        this.filePath = filePath;
        file = new RandomAccessFile(filePath, accessMode);
    }

    @Override
    public long fileLength() {
        File file = new File(filePath);
        return file.length();
    }

    @Override
    public int read() throws IOException {
        return file.read();
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
