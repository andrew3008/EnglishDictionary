package space.br1440.platform.tracing.otel.javaagent.resource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import space.br1440.platform.tracing.otel.javaagent.utils.Strings;

import java.net.InetAddress;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Резолвер {@code host.name} с семантикой <b>omit-if-unknown</b>: возвращает {@link Optional#empty()},
 * если имя хоста определить не удалось (фейковый {@code "unknown"} не пишется — он загрязняет
 * агрегации backend'ов).
 *
 * <h2>Порядок резолва</h2>
 * <ol>
 *   <li>{@code HOSTNAME} (Linux/Docker/Kubernetes) — выставляется kubelet'ом;</li>
 *   <li>{@code COMPUTERNAME} (Windows) — dev-машины;</li>
 *   <li>{@link InetAddress#getLocalHost()} в daemon-thread'е с таймаутом 1 секунда — last resort.
 *       Daemon-флаг обязателен: при зависании DNS не-daemon-поток держал бы JVM от shutdown.</li>
 *   <li>Иначе — {@link Optional#empty()} (omit).</li>
 * </ol>
 *
 * <p>Извлечено из {@code PlatformResourceProvider} для тестируемости и переиспользования.
 */
public final class HostNameResolver {

    private static final Logger log = LoggerFactory.getLogger(HostNameResolver.class);

    private static final long HOSTNAME_RESOLVE_TIMEOUT_MS = 1_000L;

    private final Function<String, String> envReader;
    private final Supplier<String> inetAddressHostName;

    public HostNameResolver() {
        this(System::getenv, HostNameResolver::resolveLocalHostNameWithTimeout);
    }

    /** Конструктор для unit-тестов: подмена источников окружения и резолва hostname'а. */
    public HostNameResolver(Function<String, String> envReader, Supplier<String> inetAddressHostName) {
        this.envReader = envReader;
        this.inetAddressHostName = inetAddressHostName;
    }

    /**
     * @return имя хоста в нижнем регистре, либо {@link Optional#empty()} если определить не удалось
     */
    public Optional<String> resolve() {
        String fromEnv = firstNonBlank(envReader.apply("HOSTNAME"), envReader.apply("COMPUTERNAME"));
        if (fromEnv != null) {
            return Optional.of(fromEnv.toLowerCase(Locale.ROOT));
        }
        String fromInet = inetAddressHostName.get();
        if (Strings.isNotBlank(fromInet)) {
            return Optional.of(fromInet.toLowerCase(Locale.ROOT));
        }
        return Optional.empty();
    }

    private static String firstNonBlank(String a, String b) {
        if (Strings.isNotBlank(a)) {
            return a;
        }
        return Strings.isNotBlank(b) ? b : null;
    }

    /**
     * Last-resort резолв через {@link InetAddress#getLocalHost()} в daemon-thread'е с таймаутом.
     * При таймауте/ошибке — {@code null}.
     */
    private static String resolveLocalHostNameWithTimeout() {
        String[] result = new String[1];
        Thread t = new Thread(() -> {
            try {
                result[0] = InetAddress.getLocalHost().getHostName();
            } catch (Throwable ignore) {
                // Любая ошибка резолва — result[0] остаётся null.
            }
        }, "platform-tracing-hostname-resolve");
        t.setDaemon(true);
        t.start();
        try {
            t.join(HOSTNAME_RESOLVE_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        if (t.isAlive()) {
            log.warn("Резолв hostname через InetAddress.getLocalHost() превысил {} мс; host.name будет опущен",
                    HOSTNAME_RESOLVE_TIMEOUT_MS);
            return null;
        }
        return result[0];
    }
}
