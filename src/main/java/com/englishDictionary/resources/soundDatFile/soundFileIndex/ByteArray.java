package com.englishDictionary.resources.soundDatFile.soundFileIndex;

import java.io.EOFException;
import java.io.IOException;

/**
 * Created by Andrew on 7/9/2017.
 */
class ByteArray {

    private byte[] bytes;
    int pos = 0;
    int end;

    public ByteArray(byte[] bytes) {
        this.bytes = bytes;
        end = bytes.length;
    }

    public boolean readBoolean() throws IOException {
        if (end - pos < 1) {
            throw new EOFException();
        }

        boolean value = Bits.getBoolean(bytes, pos);
        ++pos;
        return value;
    }

    public short readShort() throws IOException {
        if (end - pos < 2) {
            throw new EOFException();
        }

        short value = Bits.getShort(bytes, pos);
        pos += 2;
        return value;
    }

    public long readLong() throws IOException {
        if (end - pos < 8) {
            throw new EOFException();
        }

        long value = Bits.getLong(bytes, pos);
        pos += 8;
        return value;
    }

    public String readString() throws IOException {
        short length = readShort();
        if (end - pos < length) {
            throw new EOFException();
        }

        String value = new String(bytes, pos, length);
        pos += length;
        return value;
    }

}
