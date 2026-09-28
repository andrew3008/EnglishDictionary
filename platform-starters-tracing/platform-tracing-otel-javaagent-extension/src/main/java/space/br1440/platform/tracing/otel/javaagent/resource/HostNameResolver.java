package space.br1440.platform.tracing.otel.javaagent.resource;

import java.net.InetAddress;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.function.Supplier;

import space.br1440.platform.common.util.StringUtils;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogField;
import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;
import space.br1440.platform.tracing.otel.javaagent.logging.event.ResourceIdentityLogEvent;
import space.br1440.platform.tracing.otel.javaagent.resilience.PlatformThrowables;

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
        return resolveLocalHostNameWithTimeout(() -> InetAddress.getLocalHost().getHostName());
    }

    static String resolveLocalHostNameWithTimeout(Callable<String> lookup) {
        String[] result = new String[1];

        Thread t = new Thread(() -> {
            try {
                result[0] = lookup.call();
            } catch (Throwable failure) {
                PlatformThrowables.propagateIfFatal(failure);
                log.log(ResourceIdentityLogEvent.HOSTNAME_RESOLUTION_FAILED,
                        fields -> fields
                                .putString(AgentLogField.FAILURE_TYPE, failure.getClass().getName())
                                .cause(failure));
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
            log.log(ResourceIdentityLogEvent.HOSTNAME_RESOLUTION_TIMED_OUT,
                    fields -> fields.putLong(AgentLogField.TIMEOUT_MS, HOSTNAME_RESOLVE_TIMEOUT_MS));
            return null;
        }

        return result[0];
    }
}
