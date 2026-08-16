package space.br1440.platform.tracing.otel.javaagent.resource;

import java.net.InetAddress;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import space.br1440.platform.tracing.otel.javaagent.resilience.PlatformThrowables;
import space.br1440.platform.common.util.StringUtils;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class HostNameResolver {

    private static final long HOSTNAME_RESOLVE_TIMEOUT_MS = 1_000L;

    private final Function<String, String> envReader;
    private final Supplier<String> inetAddressHostName;

    public HostNameResolver() {
        this(System::getenv, HostNameResolver::resolveLocalHostNameWithTimeout);
    }

    public HostNameResolver(Function<String, String> envReader, Supplier<String> inetAddressHostName) {
        this.envReader = envReader;
        this.inetAddressHostName = inetAddressHostName;
    }

    public Optional<String> resolve() {
        String fromEnv = firstNonBlank(envReader.apply("HOSTNAME"), envReader.apply("COMPUTERNAME"));
        if (fromEnv != null) {
            return Optional.of(fromEnv.toLowerCase(Locale.ROOT));
        }

        String fromInet = inetAddressHostName.get();
        if (!StringUtils.isNullOrBlank(fromInet)) {
            return Optional.of(fromInet.toLowerCase(Locale.ROOT));
        }

        return Optional.empty();
    }

    private static String firstNonBlank(String a, String b) {
        if (!StringUtils.isNullOrBlank(a)) {
            return a;
        }

        return !StringUtils.isNullOrBlank(b) ? b : null;
    }

    private static String resolveLocalHostNameWithTimeout() {
        String[] result = new String[1];

        Thread t = new Thread(() -> {
            try {
                result[0] = InetAddress.getLocalHost().getHostName();
            } catch (Throwable failure) {
                PlatformThrowables.propagateIfFatal(failure);
                log.debug("Failed to resolve hostname via InetAddress.getLocalHost(): {}", failure.toString());
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
            log.warn("Hostname resolution via InetAddress.getLocalHost() exceeded {} ms; host.name will be omitted", HOSTNAME_RESOLVE_TIMEOUT_MS);
            return null;
        }

        return result[0];
    }
}
