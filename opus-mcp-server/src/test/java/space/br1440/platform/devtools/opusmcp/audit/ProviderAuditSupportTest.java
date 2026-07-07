package space.br1440.platform.devtools.opusmcp.audit;

import org.junit.jupiter.api.Test;
import space.br1440.platform.devtools.opusmcp.model.OpusClientException;
import space.br1440.platform.devtools.opusmcp.model.OpusResponse;
import space.br1440.platform.devtools.opusmcp.model.ProviderCallMetadata;
import space.br1440.platform.devtools.opusmcp.model.ProviderDiagnosticCategory;
import space.br1440.platform.devtools.opusmcp.model.ProviderEnvelopeKind;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderAuditSupportTest {

    @Test
    void applySuccessCopiesProviderMetadataIntoAuditBuilder() {
        OpusResponse response = new OpusResponse(
                "text",
                10,
                5,
                ProviderCallMetadata.success("req-provider-1", ProviderEnvelopeKind.OPENAI_CHAT_STRING));

        AuditRecord record = ProviderAuditSupport.applySuccess(AuditRecord.builder(), response)
                .requestId("rid-1")
                .toolName("generate_tests_with_opus")
                .status("OK")
                .build();

        String line = record.toLogString();
        assertThat(line)
                .contains("providerRequestId=req-provider-1")
                .contains("envelopeKind=openai_chat_string")
                .contains("diagnosticCategory=none")
                .contains("providerCallAttempted=true");
    }

    @Test
    void applyFailureCopiesDiagnosticMetadataWithoutRawProviderBody() {
        OpusClientException exception = new OpusClientException(
                OpusClientException.Reason.PARSE_ERROR,
                "Provider returned invalid JSON",
                ProviderCallMetadata.failure(
                        "req-body-99",
                        ProviderEnvelopeKind.NONE,
                        ProviderDiagnosticCategory.INVALID_JSON));

        AuditRecord record = ProviderAuditSupport.applyFailure(AuditRecord.builder(), exception)
                .requestId("rid-2")
                .toolName("generate_tests_with_opus")
                .status("MODEL_ERROR")
                .build();

        String line = record.toLogString();
        assertThat(line)
                .contains("providerRequestId=req-body-99")
                .contains("envelopeKind=none")
                .contains("diagnosticCategory=invalid_json")
                .contains("providerCallAttempted=true")
                .doesNotContain("Provider returned invalid JSON");
    }

    @Test
    void blankProviderMetadataFieldsRenderAsDashInLogString() {
        AuditRecord record = AuditRecord.builder()
                .requestId("rid-3")
                .toolName("generate_code_with_opus")
                .status("OK")
                .build();

        assertThat(record.toLogString())
                .contains("providerRequestId=-")
                .contains("envelopeKind=-")
                .contains("diagnosticCategory=-")
                .contains("providerCallAttempted=false");
    }
}
