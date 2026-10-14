package space.br1440.platform.tracing.autoconfigure.control.configserver;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class ConfigServerRefreshWorker implements AutoCloseable {

    static final String THREAD_NAME = "platform-tracing-config-server-refresh";
    static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);

    private final Duration closeTimeout;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            Thread.ofPlatform()
                    .name(THREAD_NAME)
                    .daemon(true)
                    .factory()
    );

    ConfigServerRefreshWorker(Duration closeTimeout) {
        this.closeTimeout = Objects.requireNonNull(closeTimeout, "closeTimeout");
    }

    Executor executor() {
        return executor;
    }

    @Override
    public void close() {
        executor.shutdown();

        try {
            if (!executor.awaitTermination(closeTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("Config Server settings refresh did not finish within {} ms on shutdown; thread={}",
                        closeTimeout.toMillis(), THREAD_NAME);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
