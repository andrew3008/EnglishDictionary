package space.br1440.platform.tracing.otel.javaagent.jmx.security;

import java.util.List;
import java.util.Map;

import space.br1440.platform.tracing.otel.javaagent.jmx.security.RemoteJmxExposurePolicy.MutationRegistrationAuthorization;
import space.br1440.platform.tracing.otel.javaagent.jmx.security.RemoteJmxExposurePolicy.MutationRegistrationPermit;

public final class RemoteJmxAuthorizationTestFixtures {

    private RemoteJmxAuthorizationTestFixtures() {
        throw new AssertionError("No instances");
    }

    public static MutationRegistrationPermit mutationRegistrationPermit() {
        RemoteJmxDeploymentDeclaration declaration =
                RemoteJmxDeploymentTestFixtures.startupVerifiedRemoteProhibited();
        RemoteJmxExposureInputs inputs = RemoteJmxExposureInputs.of(
                Map.of(),
                List.of(RemoteJmxExposureInputs.DISABLE_ATTACH_MECHANISM_ARGUMENT),
                declaration);
        MutationRegistrationAuthorization authorization = new RemoteJmxExposurePolicy(declaration)
                .authorize(inputs);
        return authorization.requirePermit();
    }
}
