package space.br1440.platform.devtools.opusmcp.audit;

import space.br1440.platform.devtools.opusmcp.model.OpusClientException;
import space.br1440.platform.devtools.opusmcp.model.OpusResponse;
import space.br1440.platform.devtools.opusmcp.model.ProviderCallMetadata;

/**
 * Copies provider metadata from {@link OpusResponse} / {@link OpusClientException} into an audit
 * record builder. Keys only; never copies model text or raw provider bodies.
 */
public final class ProviderAuditSupport {

    private ProviderAuditSupport() {
    }

    public static AuditRecord.Builder applySuccess(AuditRecord.Builder audit, OpusResponse response) {
        if (response == null) {
            return audit;
        }
        return apply(audit, response.providerMetadata());
    }

    public static AuditRecord.Builder applyFailure(AuditRecord.Builder audit, OpusClientException exception) {
        if (exception == null) {
            return audit;
        }
        return apply(audit, exception.providerMetadata());
    }

    private static AuditRecord.Builder apply(AuditRecord.Builder audit, ProviderCallMetadata metadata) {
        if (metadata == null) {
            return audit;
        }
        return audit.providerCallAttempted(metadata.providerCallAttempted())
                .providerRequestId(logValue(metadata.providerRequestId()))
                .envelopeKind(logValue(metadata.envelopeKind()))
                .diagnosticCategory(logValue(metadata.diagnosticCategory()));
    }

    private static String logValue(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        return value.replace("\r", " ").replace("\n", " ").trim();
    }
}
