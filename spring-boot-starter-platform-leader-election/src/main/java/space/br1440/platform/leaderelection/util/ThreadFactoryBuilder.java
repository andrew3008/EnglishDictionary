package space.br1440.platform.leaderelection.util;

import java.util.Objects;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public final class ThreadFactoryBuilder {

    private String nameFormat = null;
    private boolean daemon = false;

    public ThreadFactoryBuilder setNameFormat(String nameFormat) {
        if (nameFormat != null && !nameFormat.contains("%d")) {
            throw new IllegalArgumentException("NameFormat must contain '%d' for thread numbering.");
        }

        this.nameFormat = nameFormat;
        return this;
    }

    public ThreadFactoryBuilder setDaemon(boolean daemon) {
        this.daemon = daemon;
        return this;
    }

    public ThreadFactory build() {
        final String format = this.nameFormat;
        final boolean daemonFlag = this.daemon;
        final AtomicInteger count = new AtomicInteger(0);

        return r -> {
            Objects.requireNonNull(r, "Runnable must not be null");

            Thread t = new Thread(r);
            if (format != null) {
                t.setName(String.format(format, count.getAndIncrement()));
            }
            t.setDaemon(daemonFlag);
            return t;
        };
    }

}
