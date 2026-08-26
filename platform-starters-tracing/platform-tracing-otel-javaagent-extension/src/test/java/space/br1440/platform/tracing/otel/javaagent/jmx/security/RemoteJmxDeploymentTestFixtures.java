package space.br1440.platform.tracing.otel.javaagent.jmx.security;

import java.util.List;

import space.br1440.platform.tracing.otel.javaagent.jmx.security.RemoteJmxDeploymentDeclaration.Mode;

/**
 * Тестовый источник деклараций, подтверждённых неизменяемыми startup JVM arguments.
 *
 * <p>Fixture использует тот же строгий parser, что и production-код, но передаёт ему
 * детерминированный список аргументов.</p>
 */
public final class RemoteJmxDeploymentTestFixtures {

    private RemoteJmxDeploymentTestFixtures() {
        throw new AssertionError("No instances");
    }

    public static RemoteJmxDeploymentDeclaration startupVerifiedRemoteProhibited() {
        return declaration(Mode.REMOTE_PROHIBITED, false, false);
    }

    public static RemoteJmxDeploymentDeclaration startupVerifiedPlatformConnector(
            boolean listenerConfirmed,
            boolean authorizationForwarderInstalled) {
        return declaration(
                Mode.PLATFORM_OWNED_CERTIFIED_CONNECTOR,
                listenerConfirmed,
                authorizationForwarderInstalled);
    }

    public static RemoteJmxDeploymentDeclaration startupVerifiedExternalConnector(
            boolean listenerConfirmed) {
        return declaration(
                Mode.EXTERNAL_OR_BUILT_IN_CONNECTOR,
                listenerConfirmed,
                false);
    }

    public static RemoteJmxDeploymentDeclaration startupVerifiedUndeclared() {
        return declaration(Mode.UNDECLARED, false, false);
    }

    public static RemoteJmxExposurePolicy startupVerifiedRemoteProhibitedPolicy() {
        return RemoteJmxExposurePolicy.fromStartupArguments(
                startupArguments(Mode.REMOTE_PROHIBITED, false, false));
    }

    public static RemoteJmxExposurePolicy startupEvidenceUnknownPolicy() {
        return RemoteJmxExposurePolicy.fromStartupArguments(List.of());
    }

    private static RemoteJmxDeploymentDeclaration declaration(
            Mode mode,
            boolean listenerConfirmed,
            boolean authorizationForwarderInstalled) {
        return RemoteJmxDeploymentDeclaration.fromStartupArguments(
                startupArguments(mode, listenerConfirmed, authorizationForwarderInstalled));
    }

    public static List<String> startupArguments(
            Mode mode,
            boolean listenerConfirmed,
            boolean authorizationForwarderInstalled) {
        return List.of(
                "-D" + RemoteJmxDeploymentDeclaration.MODE_PROPERTY + "=" + mode.name(),
                "-D" + RemoteJmxDeploymentDeclaration.LISTENER_CONFIRMED_PROPERTY
                        + "=" + listenerConfirmed,
                "-D" + RemoteJmxDeploymentDeclaration.AUTHORIZATION_FORWARDER_INSTALLED_PROPERTY
                        + "=" + authorizationForwarderInstalled,
                RemoteJmxExposureInputs.DISABLE_ATTACH_MECHANISM_ARGUMENT);
    }
}
