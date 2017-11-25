package com.englishDictionary.webServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;

/**
 * Created by Andrew on 8/27/2016.
 */
public class ByteArrayOutputStream extends OutputStream {

    private byte[] buffer;
    private int count;

    public ByteArrayOutputStream(int size) {
        if (size < 0) {
            throw new IllegalArgumentException("Negative initial writedBytes: " + size);
        }
        buffer = new byte[size];
    }

    private static byte[] copyOf(byte[] src, int length) {
        byte[] dest = new byte[length];
        System.arraycopy(src, 0, dest, 0, Math.min(src.length, length));
        return dest;
    }

    @Override
    public void write(int b) {
        int newcount = count + 1;
        if (newcount > buffer.length) {
            buffer = copyOf(buffer, Math.max(buffer.length << 1, newcount));
        }
        buffer[count] = (byte) b;
        count = newcount;
    }

    @Override
    public void write(byte b[]) throws IOException {
        write(b, 0, b.length);
    }

    @Override
    public void write(byte b[], int off, int len) {
        if ((off < 0) || (off > b.length) || (len < 0) || ((off + len) > b.length) || ((off + len) < 0)) {
            throw new IndexOutOfBoundsException();
        }

        if (len == 0) {
            return;
        }

        int newcount = count + len;
        if (newcount > buffer.length) {
            buffer = copyOf(buffer, Math.max(buffer.length << 1, newcount));
        }
        System.arraycopy(b, off, buffer, count, len);
        count = newcount;
    }

    public void write(String str) throws IOException {
        write(str.getBytes());
    }

    public int writedBytes() {
        return count;
    }

    public void writeTo(OutputStream out) throws IOException {
        out.write(buffer, 0, count);
    }

    public int size() {
        return count;
    }

    public byte[] toByteArray() {
        return buffer;
    }

    public String toString() {
        return new String(buffer, 0, count);
    }

    public String toString(String charset) throws UnsupportedEncodingException {
        return new String(buffer, 0, count, charset);
    }

    public void reset() {
        count = 0;
    }

    public void close() throws IOException {
    }

}
