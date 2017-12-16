package com.englishDictionary.webServices.excel;

import com.englishDictionary.webServer.ByteArrayOutputStream;
import io.netty.buffer.ByteBuf;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

public class BufferListOfWordsFromExcel {

    private static final int BUFFER_IO_INIT_LENGTH = 2048;

    private ByteArrayOutputStream outputStream = new ByteArrayOutputStream(BUFFER_IO_INIT_LENGTH);
    public static final BufferListOfWordsFromExcel INSTANCE = new BufferListOfWordsFromExcel();

    public void updateBuffer(ByteBuf sourceBuffer) {
        try {
            outputStream.reset();
            sourceBuffer.getBytes(0, outputStream, sourceBuffer.readableBytes());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public InputStream getInputStream() {
        return new ByteArrayInputStream(outputStream.toByteArray(), 0, outputStream.size());
    }

}
