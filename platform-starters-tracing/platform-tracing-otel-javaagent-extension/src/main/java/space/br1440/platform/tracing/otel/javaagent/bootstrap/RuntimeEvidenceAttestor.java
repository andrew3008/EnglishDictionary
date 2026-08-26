package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionRuntimeEvidence;

@FunctionalInterface
interface RuntimeEvidenceAttestor {

    ExtensionRuntimeEvidence attest(String extensionVersion);
}
