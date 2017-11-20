package com.englishDictionary.resources.soundDatFile.soundFileIndex;

import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Created by Andrew on 7/10/2017.
 */
class FileOutputByteStream {

    private FileOutputStream file;
    private byte[] buffer = new byte[8];

    public FileOutputByteStream(FileOutputStream file) {
        this.file = file;
    }

    public void writeBoolean(boolean value) throws IOException {
        Bits.putBoolean(buffer, 0, value);
        file.write(buffer, 0, 1);
    }

    public void writeString(String value) throws IOException {
        byte[] data = value.getBytes();
        writeShort((short) data.length);
        file.write(data, 0, data.length);
    }

    public void writeShort(short value) throws IOException {
        Bits.putShort(buffer, 0, value);
        file.write(buffer, 0, 2);
    }

    public void writeLong(long value) throws IOException {
        Bits.putLong(buffer, 0, value);
        file.write(buffer, 0, 8);
    }

}
