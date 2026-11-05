package space.br1440.platform.tracing.otel.javaagent.bootstrap.failure;

import java.util.Objects;

import space.br1440.platform.tracing.otel.javaagent.bootstrap.resources.BootstrapResources;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.session.AutoconfigureSession;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionRuntimeEvidence;

public record FailureContext(FailureSite site,
                      String failureCode,
                      Throwable cause,
                      FailureAdmission admission,
                      AutoconfigureSession ownedSession,
                      BootstrapResources resources,
                      String batchId,
                      ExtensionRuntimeEvidence evidence) {

    public FailureContext {
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(failureCode, "failureCode");
        Objects.requireNonNull(cause, "cause");
        Objects.requireNonNull(admission, "admission");
        Objects.requireNonNull(batchId, "batchId");
        Objects.requireNonNull(evidence, "evidence");
    }
}
