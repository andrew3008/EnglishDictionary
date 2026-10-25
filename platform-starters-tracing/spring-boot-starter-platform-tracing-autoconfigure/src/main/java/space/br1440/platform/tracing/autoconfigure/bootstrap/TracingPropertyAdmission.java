package space.br1440.platform.tracing.autoconfigure.bootstrap;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.BoundPropertiesTrackingBindHandler;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.IterableConfigurationPropertySource;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;

import space.br1440.platform.tracing.autoconfigure.TracingProperties;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class TracingPropertyAdmission {

    private static final String CONFIG_SERVER_SOURCE_PREFIX = "configserver:";
    private static final String PLATFORM_TRACING_PREFIX = "platform.tracing";

    private static final ConfigurationPropertyName PLATFORM_TRACING = ConfigurationPropertyName.of(PLATFORM_TRACING_PREFIX);
    private static final ConfigurationPropertyName POLICY = ConfigurationPropertyName.of("platform.tracing.policy");
    private static final ConfigurationPropertyName CONTROL_CHANNEL = ConfigurationPropertyName.of(
            TracingBootstrapPropertyNames.CONTROL_CHANNEL
    );

    private static final List<ConfigurationPropertyName> EXPLICIT_SPRING_NAMES = List.of(
            ConfigurationPropertyName.of(TracingBootstrapPropertyNames.ENABLED),
            CONTROL_CHANNEL,
            ConfigurationPropertyName.of(TracingBootstrapPropertyNames.KAFKA_PROPAGATE_PLATFORM_HEADERS),
            ConfigurationPropertyName.of(TracingBootstrapPropertyNames.SUPPRESS_MICROMETER_HTTP_OBSERVATIONS)
    );

    public static List<Violation> check(ConfigurableEnvironment environment, TracingControlChannel controlChannel) {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(controlChannel, "controlChannel");

        Set<ConfigurationPropertyName> boundSpringNames = boundSpringNames(environment);
        List<Violation> violations = new ArrayList<>();
        for (ConfigurationPropertySource source : ConfigurationPropertySources.get(environment)) {
            if (!(source instanceof IterableConfigurationPropertySource iterable)) {
                continue;
            }

            Object underlying = iterable.getUnderlyingSource();
            if (!(underlying instanceof PropertySource<?> propertySource)) {
                continue;
            }

            SourceKind sourceKind = sourceKind(propertySource.getName());
            for (ConfigurationPropertyName name : iterable) {
                if (!isUnder(PLATFORM_TRACING, name)) {
                    continue;
                }

                if (sourceKind == SourceKind.PROCESS && !isUnder(POLICY, name)) {
                    continue;
                }

                Owner owner = ownerOf(name, boundSpringNames);
                if (!admitted(name, owner, sourceKind, controlChannel)) {
                    violations.add(new Violation(reportedName(propertySource, name), owner, propertySource.getName()));
                }
            }
        }
        return List.copyOf(violations);
    }

    public static String describe(TracingControlChannel controlChannel, List<Violation> violations) {
        Objects.requireNonNull(controlChannel, "controlChannel");
        Objects.requireNonNull(violations, "violations");

        StringBuilder message = new StringBuilder();
        for (Violation violation : violations) {
            if (!message.isEmpty()) {
                message.append(System.lineSeparator());
            }

            message.append(describe(controlChannel, violation));
        }

        return message.toString();
    }

    private static Set<ConfigurationPropertyName> boundSpringNames(ConfigurableEnvironment environment) {
        Set<ConfigurationPropertyName> names = new LinkedHashSet<>();
        Binder.get(environment).bind(
                PLATFORM_TRACING_PREFIX,
                Bindable.of(TracingProperties.class),
                new BoundPropertiesTrackingBindHandler(property -> names.add(property.getName()))
        );
        return names;
    }

    private static Owner ownerOf(ConfigurationPropertyName name, Set<ConfigurationPropertyName> boundSpringNames) {
        if (isUnder(POLICY, name)) {
            return Owner.POLICY;
        }

        if (isExplicitSpringName(name) || isBoundSpringName(name, boundSpringNames)) {
            return Owner.SPRING;
        }

        return Owner.AGENT;
    }

    private static boolean isExplicitSpringName(ConfigurationPropertyName name) {
        for (ConfigurationPropertyName explicitName : EXPLICIT_SPRING_NAMES) {
            if (explicitName.equals(name)) {
                return true;
            }
        }

        return false;
    }

    private static boolean isBoundSpringName(ConfigurationPropertyName name,
                                             Set<ConfigurationPropertyName> boundSpringNames) {
        for (ConfigurationPropertyName boundName : boundSpringNames) {
            if (boundName.equals(name) || boundName.isAncestorOf(name)) {
                return true;
            }
        }

        return false;
    }

    private static boolean admitted(ConfigurationPropertyName name,
                                    Owner owner,
                                    SourceKind sourceKind,
                                    TracingControlChannel controlChannel) {
        if (CONTROL_CHANNEL.equals(name) && sourceKind == SourceKind.CONFIG_SERVER) {
            return false;
        }

        return switch (owner) {
            case SPRING -> true;
            case POLICY -> (sourceKind == SourceKind.CONFIG_SERVER) && (controlChannel == TracingControlChannel.CONFIG_SERVER);
            case AGENT -> sourceKind == SourceKind.PROCESS;
        };
    }

    private static SourceKind sourceKind(String sourceName) {
        if (StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME.equals(sourceName)
                || StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME.equals(sourceName)) {
            return SourceKind.PROCESS;
        }

        if (sourceName.startsWith(CONFIG_SERVER_SOURCE_PREFIX)) {
            return SourceKind.CONFIG_SERVER;
        }

        return SourceKind.APPLICATION;
    }

    private static boolean isUnder(ConfigurationPropertyName prefix, ConfigurationPropertyName name) {
        return prefix.equals(name) || prefix.isAncestorOf(name);
    }

    private static String reportedName(PropertySource<?> propertySource, ConfigurationPropertyName canonical) {
        if (propertySource instanceof EnumerablePropertySource<?> enumerable) {
            for (String rawName : enumerable.getPropertyNames()) {
                if (!rawName.startsWith(PLATFORM_TRACING_PREFIX)) {
                    continue;
                }

                ConfigurationPropertyName adapted = ConfigurationPropertyName.isValid(rawName)
                        ? ConfigurationPropertyName.of(rawName)
                        : ConfigurationPropertyName.adapt(rawName, '.');
                if (adapted.equals(canonical)) {
                    return rawName;
                }
            }
        }

        return canonical.toString();
    }

    private static String describe(TracingControlChannel controlChannel, Violation violation) {
        return switch (violation.owner()) {
            case AGENT -> """
                Property '%s' is owned by AGENT and is not admitted from source='%s'. \
                Agent-owned keys are set with environment variables or system properties; \
                Spring does not read them."""
                    .formatted(violation.propertyName(), violation.sourceName());

            case POLICY -> """
                Property '%s' is owned by POLICY and is not admitted from source='%s' \
                when control channel is %s. Policy keys are admitted only from a Config Server source \
                when the control channel is CONFIG_SERVER."""
                    .formatted(violation.propertyName(), violation.sourceName(), controlChannel);

            case SPRING -> """
                Property '%s' is owned by SPRING and is not admitted from source='%s'. \
                The control channel must be configured outside Config Server."""
                    .formatted(violation.propertyName(), violation.sourceName());
        };
    }

    public enum Owner {
        SPRING,
        POLICY,
        AGENT
    }

    public record Violation(String propertyName, Owner owner, String sourceName) {
    }

    private enum SourceKind {
        PROCESS,
        CONFIG_SERVER,
        APPLICATION
    }
}
