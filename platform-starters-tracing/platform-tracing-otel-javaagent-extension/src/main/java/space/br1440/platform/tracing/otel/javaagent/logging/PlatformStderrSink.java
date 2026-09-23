package space.br1440.platform.tracing.otel.javaagent.logging;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

final class PlatformStderrSink {

    private final OutputStream stream;
    private final ReentrantLock writeLock = new ReentrantLock();

    PlatformStderrSink() {
        this(new FileOutputStream(FileDescriptor.err));
    }

    PlatformStderrSink(OutputStream stream) {
        this.stream = Objects.requireNonNull(stream, "stream");
    }

    void write(byte[] payload) throws IOException {
        Objects.requireNonNull(payload, "payload");

        writeLock.lock();
        try {
            stream.write(payload);
        } finally {
            writeLock.unlock();
        }
    }
}
