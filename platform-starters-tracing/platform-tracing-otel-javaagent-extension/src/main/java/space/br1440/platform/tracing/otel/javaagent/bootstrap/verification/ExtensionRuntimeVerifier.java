package space.br1440.platform.tracing.otel.javaagent.bootstrap.verification;

import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionRuntimeEvidence;

@FunctionalInterface
public interface ExtensionRuntimeVerifier {

    ExtensionRuntimeEvidence verify(String extensionVersion);

    static ExtensionRuntimeVerifier forCurrentJvm() {
        return ControlledAgentVerifier.forCurrentJvm();
    }
}
