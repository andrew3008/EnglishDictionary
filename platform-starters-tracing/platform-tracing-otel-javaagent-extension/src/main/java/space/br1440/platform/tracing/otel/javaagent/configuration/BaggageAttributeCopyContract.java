package space.br1440.platform.tracing.otel.javaagent.configuration;

import java.util.List;
import java.util.Objects;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigurationException;
import lombok.experimental.UtilityClass;

@UtilityClass
public final class BaggageAttributeCopyContract {

    private static final List<String> INCLUDE_PROPERTIES = List.of(
            OtelSdkPropertyNames.SPAN_ATTRIBUTES_COPY_FROM_BAGGAGE_INCLUDE,
            OtelSdkPropertyNames.LOG_ATTRIBUTES_COPY_FROM_BAGGAGE_INCLUDE
    );

    public static void requireDisabled(ConfigProperties config) {
        Objects.requireNonNull(config, "config");

        for (String propertyName : INCLUDE_PROPERTIES) {
            if (config.getList(propertyName).isEmpty()) {
                continue;
            }

            throw new ConfigurationPropertyException(
                    propertyName,
                    new ConfigurationException(
                            propertyName
                                    + " must be empty; non-empty values copy baggage into attributes"
                                    + " outside the platform baggage policy"
                    )
            );
        }
    }
}
