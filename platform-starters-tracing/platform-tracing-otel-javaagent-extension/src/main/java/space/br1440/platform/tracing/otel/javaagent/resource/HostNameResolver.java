package space.br1440.platform.tracing.otel.javaagent.resource;

import java.net.InetAddress;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import space.br1440.platform.tracing.otel.javaagent.logging.AgentEventCode;
import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;
import space.br1440.platform.tracing.otel.javaagent.resilience.PlatformThrowables;
import space.br1440.platform.common.util.StringUtils;

final class HostNameResolver {

    private static final PlatformAgentLogger log = PlatformAgentLogger.get(HostNameResolver.class);

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
        if (StringUtils.hasText(fromInet)) {
            return Optional.of(fromInet.toLowerCase(Locale.ROOT));
        }

        return Optional.empty();
    }

    private static String firstNonBlank(String a, String b) {
        if (StringUtils.hasText(a)) {
            return a;
        }

        return StringUtils.hasText(b) ? b : null;
    }

    private static String resolveLocalHostNameWithTimeout() {
        String[] result = new String[1];

        Thread t = new Thread(() -> {
            try {
                result[0] = InetAddress.getLocalHost().getHostName();
            } catch (Throwable failure) {
                PlatformThrowables.propagateIfFatal(failure);
                if (log.isDebugEnabled()) {
                    log.debug(AgentEventCode.BOOT_HOSTNAME_FAILED, "Hostname resolution failed", "detail", failure.toString());
                }
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
            log.warn(AgentEventCode.BOOT_HOSTNAME_TIMEOUT, "Hostname resolution timed out", "timeoutMs",
                    String.valueOf(HOSTNAME_RESOLVE_TIMEOUT_MS));
            return null;
        }

        return result[0];
    }
}
