package space.br1440.platform.tracing.otel.javaagent.configuration;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigurationException;
import lombok.experimental.UtilityClass;

@UtilityClass
public final class PropagatorConfigurationContract {

    public static final String NONE = "none";
    public static final String PLATFORM_PROPAGATOR = "platform-trace-control";
    public static final Set<String> SUPPORTED_PROPAGATORS = Set.of("tracecontext", "baggage", "b3", "b3multi", PLATFORM_PROPAGATOR, NONE);
    private static final String SUPPORTED_PROPAGATORS_DESCRIPTION = new TreeSet<>(SUPPORTED_PROPAGATORS).toString();

    public static void requireSupported(ConfigProperties config) {
        Objects.requireNonNull(config, "config");

        List<String> configured = config.getList(OtelSdkPropertyNames.PROPAGATORS);
        Set<String> unsupported = new TreeSet<>(configured);
        unsupported.removeAll(SUPPORTED_PROPAGATORS);
        if (unsupported.isEmpty()) {
            return;
        }

        throw new ConfigurationPropertyException(
                OtelSdkPropertyNames.PROPAGATORS,
                new ConfigurationException(
                        OtelSdkPropertyNames.PROPAGATORS
                                + " contains unsupported propagators " + unsupported
                                + "; these propagators carry baggage outside the platform policy"
                                + "; supported: " + SUPPORTED_PROPAGATORS_DESCRIPTION
                )
        );
    }
}
