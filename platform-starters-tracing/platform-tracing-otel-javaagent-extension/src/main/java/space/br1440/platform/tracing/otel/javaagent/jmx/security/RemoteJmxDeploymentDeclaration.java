package space.br1440.platform.tracing.otel.javaagent.jmx.security;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;

public final class RemoteJmxDeploymentDeclaration {

    public static final String MODE_PROPERTY = "platform.tracing.jmx.remote.mode";
    public static final String LISTENER_CONFIRMED_PROPERTY = "platform.tracing.jmx.remote.listener-confirmed";
    public static final String AUTHORIZATION_FORWARDER_INSTALLED_PROPERTY = "platform.tracing.jmx.remote.authorization-forwarder-installed";

    private static final String DECLARATION_PROPERTY_PREFIX = "platform.tracing.jmx.remote.";
    private static final Set<String> DECLARATION_PROPERTIES = Set.of(
            MODE_PROPERTY,
            LISTENER_CONFIRMED_PROPERTY,
            AUTHORIZATION_FORWARDER_INSTALLED_PROPERTY
    );

    public enum Mode {
        REMOTE_PROHIBITED,
        PLATFORM_OWNED_CERTIFIED_CONNECTOR,
        EXTERNAL_OR_BUILT_IN_CONNECTOR,
        UNDECLARED,
        UNKNOWN
    }

    public enum AttestationSource {
        STARTUP_ARGUMENTS_VERIFIED,
        CONFIG_PROPERTIES_UNVERIFIED,
        PROGRAMMATIC_DECLARATION_UNVERIFIED,
        UNAVAILABLE
    }

    private final Mode mode;
    private final boolean listenerConfirmed;
    private final boolean authorizationForwarderInstalled;
    private final boolean dynamicConnectorProhibited;
    private final AttestationSource attestationSource;

    private RemoteJmxDeploymentDeclaration(Mode mode,
                                           boolean listenerConfirmed,
                                           boolean authorizationForwarderInstalled,
                                           boolean dynamicConnectorProhibited,
                                           AttestationSource attestationSource) {
        this.mode = Objects.requireNonNull(mode, "Deployment JMX mode must not be null");
        this.listenerConfirmed = listenerConfirmed;
        this.authorizationForwarderInstalled = authorizationForwarderInstalled;
        this.dynamicConnectorProhibited = dynamicConnectorProhibited;
        this.attestationSource = Objects.requireNonNull(
                attestationSource,
                "Deployment JMX attestation source must not be null"
        );
    }

    public static RemoteJmxDeploymentDeclaration remoteProhibited() {
        return new RemoteJmxDeploymentDeclaration(
                Mode.REMOTE_PROHIBITED,
                false,
                false,
                true,
                AttestationSource.PROGRAMMATIC_DECLARATION_UNVERIFIED
        );
    }

    public static RemoteJmxDeploymentDeclaration undeclared() {
        return new RemoteJmxDeploymentDeclaration(
                Mode.UNDECLARED,
                false,
                false,
                false,
                AttestationSource.UNAVAILABLE);
    }

    public static RemoteJmxDeploymentDeclaration unknown() {
        return new RemoteJmxDeploymentDeclaration(
                Mode.UNKNOWN,
                false,
                false,
                false,
                AttestationSource.UNAVAILABLE);
    }

    public static RemoteJmxDeploymentDeclaration from(ConfigProperties config) {
        Objects.requireNonNull(config, "ConfigProperties must not be null");

        try {
            String rawMode = config.getString(MODE_PROPERTY);
            if (rawMode == null || rawMode.isBlank()) {
                return undeclared();
            }

            Mode parsedMode;
            try {
                parsedMode = Mode.valueOf(rawMode.trim());
            } catch (IllegalArgumentException exception) {
                return configurationUnknown();
            }

            DeclaredFlag listener = declaredFlag(config.getString(LISTENER_CONFIRMED_PROPERTY));
            DeclaredFlag forwarder = declaredFlag(
                    config.getString(AUTHORIZATION_FORWARDER_INSTALLED_PROPERTY));
            if (listener == DeclaredFlag.INVALID || forwarder == DeclaredFlag.INVALID) {
                return configurationUnknown();
            }

            return switch (parsedMode) {
                case REMOTE_PROHIBITED -> listener.set() || forwarder.set()
                        ? configurationUnknown()
                        : configurationDeclaration(
                        Mode.REMOTE_PROHIBITED,
                        false,
                        false,
                        true);

                case PLATFORM_OWNED_CERTIFIED_CONNECTOR -> configurationDeclaration(
                        Mode.PLATFORM_OWNED_CERTIFIED_CONNECTOR,
                        listener.set(),
                        forwarder.set(),
                        false);

                case EXTERNAL_OR_BUILT_IN_CONNECTOR -> forwarder.set()
                        ? configurationUnknown()
                        : configurationDeclaration(
                        Mode.EXTERNAL_OR_BUILT_IN_CONNECTOR,
                        listener.set(),
                        false,
                        false);

                case UNDECLARED -> listener.set() || forwarder.set()
                        ? configurationUnknown()
                        : configurationDeclaration(
                        Mode.UNDECLARED,
                        false,
                        false,
                        false);

                case UNKNOWN -> configurationUnknown();
            };
        } catch (RuntimeException exception) {
            return configurationUnknown();
        }
    }

    static RemoteJmxDeploymentDeclaration fromStartupArguments(List<String> arguments) {
        Objects.requireNonNull(arguments, "JVM startup arguments must not be null");

        Map<String, String> values = new LinkedHashMap<>();
        boolean invalid = false;
        for (String argument : arguments) {
            if (argument == null) {
                invalid = true;
                continue;
            }

            String prefix = "-D" + DECLARATION_PROPERTY_PREFIX;
            if (!argument.startsWith(prefix)) {
                continue;
            }

            int equals = argument.indexOf('=');
            if (equals < 0 || equals == argument.length() - 1) {
                invalid = true;
                continue;
            }

            String name = argument.substring(2, equals);
            String value = argument.substring(equals + 1);
            if (!DECLARATION_PROPERTIES.contains(name)
                    || values.putIfAbsent(name, value) != null) {
                invalid = true;
            }
        }

        if (invalid || values.size() != DECLARATION_PROPERTIES.size()) {
            return startupArgumentsUnknown();
        }

        Mode mode;
        try {
            mode = Mode.valueOf(values.get(MODE_PROPERTY));
        } catch (IllegalArgumentException exception) {
            return startupArgumentsUnknown();
        }

        DeclaredFlag listener = exactDeclaredFlag(values.get(LISTENER_CONFIRMED_PROPERTY));
        DeclaredFlag forwarder = exactDeclaredFlag(values.get(AUTHORIZATION_FORWARDER_INSTALLED_PROPERTY));
        if (listener == DeclaredFlag.INVALID || forwarder == DeclaredFlag.INVALID) {
            return startupArgumentsUnknown();
        }

        return switch (mode) {
            case REMOTE_PROHIBITED -> listener.set() || forwarder.set()
                    ? startupArgumentsUnknown()
                    : startupArgumentsDeclaration(mode, false, false, true);

            case PLATFORM_OWNED_CERTIFIED_CONNECTOR -> startupArgumentsDeclaration(
                    mode,
                    listener.set(),
                    forwarder.set(),
                    false);

            case EXTERNAL_OR_BUILT_IN_CONNECTOR -> forwarder.set()
                    ? startupArgumentsUnknown()
                    : startupArgumentsDeclaration(
                    mode,
                    listener.set(),
                    false,
                    false);

            case UNDECLARED -> listener.set() || forwarder.set()
                    ? startupArgumentsUnknown()
                    : startupArgumentsDeclaration(mode, false, false, false);

            case UNKNOWN -> startupArgumentsUnknown();
        };
    }

    public Mode mode() {
        return mode;
    }

    public boolean listenerConfirmed() {
        return listenerConfirmed;
    }

    public boolean authorizationForwarderInstalled() {
        return authorizationForwarderInstalled;
    }

    public boolean dynamicConnectorProhibited() {
        return dynamicConnectorProhibited;
    }

    public AttestationSource attestationSource() {
        return attestationSource;
    }

    boolean hasVerifiedDeploymentSource() {
        return attestationSource == AttestationSource.STARTUP_ARGUMENTS_VERIFIED;
    }

    private static RemoteJmxDeploymentDeclaration configurationDeclaration(Mode mode,
                                                                           boolean listenerConfirmed,
                                                                           boolean authorizationForwarderInstalled,
                                                                           boolean dynamicConnectorProhibited) {
        return new RemoteJmxDeploymentDeclaration(
                mode,
                listenerConfirmed,
                authorizationForwarderInstalled,
                dynamicConnectorProhibited,
                AttestationSource.CONFIG_PROPERTIES_UNVERIFIED
        );
    }

    private static RemoteJmxDeploymentDeclaration configurationUnknown() {
        return configurationDeclaration(Mode.UNKNOWN, false, false, false);
    }

    private static RemoteJmxDeploymentDeclaration startupArgumentsDeclaration(
            Mode mode,
            boolean listenerConfirmed,
            boolean authorizationForwarderInstalled,
            boolean dynamicConnectorProhibited) {
        return new RemoteJmxDeploymentDeclaration(
                mode,
                listenerConfirmed,
                authorizationForwarderInstalled,
                dynamicConnectorProhibited,
                AttestationSource.STARTUP_ARGUMENTS_VERIFIED
        );
    }

    private static RemoteJmxDeploymentDeclaration startupArgumentsUnknown() {
        return startupArgumentsDeclaration(Mode.UNKNOWN, false, false, false);
    }

    private static DeclaredFlag declaredFlag(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return DeclaredFlag.FALSE;
        }

        if (rawValue.equalsIgnoreCase("true")) {
            return DeclaredFlag.TRUE;
        }

        if (rawValue.equalsIgnoreCase("false")) {
            return DeclaredFlag.FALSE;
        }

        return DeclaredFlag.INVALID;
    }

    private static DeclaredFlag exactDeclaredFlag(String rawValue) {
        if ("true".equals(rawValue)) {
            return DeclaredFlag.TRUE;
        }

        if ("false".equals(rawValue)) {
            return DeclaredFlag.FALSE;
        }

        return DeclaredFlag.INVALID;
    }

    private enum DeclaredFlag {
        TRUE,
        FALSE,
        INVALID;

        boolean set() {
            return (this == TRUE);
        }
    }
}
