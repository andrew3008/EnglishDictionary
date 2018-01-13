package com.englishDictionary.resourceReaders.resourceReader;

import java.io.IOException;
import java.io.OutputStream;

public interface SEDReader {
    long fileLength();
    int read() throws IOException;
    int	read(byte[] b) throws IOException;
    int read(byte[] b, int off, int len) throws IOException;
    int read(OutputStream outputStream, int len) throws IOException;
    void seek(long position) throws IOException;
    void close() throws IOException;
}
