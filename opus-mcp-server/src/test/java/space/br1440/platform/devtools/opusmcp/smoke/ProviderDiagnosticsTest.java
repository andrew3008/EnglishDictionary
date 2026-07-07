package space.br1440.platform.devtools.opusmcp.smoke;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderDiagnosticsTest {

    @Test
    void cloudflare502IsProviderDown() {
        String body = "error code: 502";
        assertThat(ProviderDiagnostics.classify(502, false, body))
                .isEqualTo(ProviderDiagnosticCategory.PROVIDER_DOWN);
    }

    @Test
    void otherUpstream5xxIsProviderDown() {
        assertThat(ProviderDiagnostics.classify(500, false, null)).isEqualTo(ProviderDiagnosticCategory.PROVIDER_DOWN);
        assertThat(ProviderDiagnostics.classify(503, false, null)).isEqualTo(ProviderDiagnosticCategory.PROVIDER_DOWN);
        assertThat(ProviderDiagnostics.classify(504, false, null)).isEqualTo(ProviderDiagnosticCategory.PROVIDER_DOWN);
        assertThat(ProviderDiagnostics.classify(599, false, null)).isEqualTo(ProviderDiagnosticCategory.PROVIDER_DOWN);
    }

    @Test
    void authErrors() {
        assertThat(ProviderDiagnostics.classify(401, false, null)).isEqualTo(ProviderDiagnosticCategory.AUTH_ERROR);
        assertThat(ProviderDiagnostics.classify(403, false, null)).isEqualTo(ProviderDiagnosticCategory.AUTH_ERROR);
    }

    @Test
    void rateLimitOrQuota() {
        assertThat(ProviderDiagnostics.classify(429, false, null))
                .isEqualTo(ProviderDiagnosticCategory.RATE_LIMIT_OR_QUOTA);
    }

    @Test
    void requestShapeErrorFor400() {
        assertThat(ProviderDiagnostics.classify(400, false, "bad request"))
                .isEqualTo(ProviderDiagnosticCategory.REQUEST_SHAPE_ERROR);
    }

    @Test
    void notFoundRefinesByBody() {
        assertThat(ProviderDiagnostics.classify(404, false, "no such path"))
                .isEqualTo(ProviderDiagnosticCategory.REQUEST_SHAPE_ERROR);
        assertThat(ProviderDiagnostics.classify(404, false, "model not found: claude-opus-4-8"))
                .isEqualTo(ProviderDiagnosticCategory.MODEL_ROUTE_DOWN);
    }

    @Test
    void requestTimeoutIsNetwork() {
        assertThat(ProviderDiagnostics.classify(408, false, null))
                .isEqualTo(ProviderDiagnosticCategory.NETWORK_ERROR);
        assertThat(ProviderDiagnostics.classifyNetworkFailure())
                .isEqualTo(ProviderDiagnosticCategory.NETWORK_ERROR);
    }

    @Test
    void unknownNon2xxIsUnknown() {
        assertThat(ProviderDiagnostics.classify(418, false, "teapot"))
                .isEqualTo(ProviderDiagnosticCategory.UNKNOWN_PROVIDER_ERROR);
    }

    @Test
    void twoXxParseOkIsOkOtherwiseParseError() {
        assertThat(ProviderDiagnostics.classify(200, true, "{}")).isEqualTo(ProviderDiagnosticCategory.OK);
        assertThat(ProviderDiagnostics.classify(200, false, "garbage"))
                .isEqualTo(ProviderDiagnosticCategory.RESPONSE_PARSE_ERROR);
    }

    @Test
    void emptyBodyPreview() {
        assertThat(ProviderDiagnostics.previewBody(null)).isEqualTo("<empty>");
        assertThat(ProviderDiagnostics.previewBody("")).isEqualTo("<empty>");
        assertThat(ProviderDiagnostics.previewBody("   \n\t ")).isEqualTo("<empty>");
    }

    @Test
    void previewIsTruncatedAndMarked() {
        String body = "x".repeat(5000);
        String preview = ProviderDiagnostics.previewBody(body, 1000);
        assertThat(preview).endsWith("...[truncated]");
        assertThat(preview.length()).isEqualTo(1000 + "...[truncated]".length());
    }

    @Test
    void previewCollapsesWhitespaceToSingleLine() {
        assertThat(ProviderDiagnostics.previewBody("error\n  code:   502\n")).isEqualTo("error code: 502");
    }

    @Test
    void previewMasksApiKeyAssignment() {
        String preview = ProviderDiagnostics.previewBody("x-api-key=sk-secret-value-123456 trailing");
        assertThat(preview).doesNotContain("sk-secret-value-123456");
        assertThat(preview).contains("[REDACTED]");
    }

    @Test
    void previewMasksBearerToken() {
        String preview = ProviderDiagnostics.previewBody("Authorization: Bearer abcdef1234567890token");
        assertThat(preview).doesNotContain("abcdef1234567890token");
        assertThat(preview).contains("[REDACTED]");
    }

    @Test
    void previewMasksPrivateKeyBlock() {
        String body = "-----BEGIN PRIVATE KEY-----\nMIIBVAIBADANBg\n-----END PRIVATE KEY-----";
        String preview = ProviderDiagnostics.previewBody(body);
        assertThat(preview).doesNotContain("MIIBVAIBADANBg");
        assertThat(preview).contains("[REDACTED]");
    }

    @Test
    void statusDescriptions() {
        assertThat(ProviderDiagnostics.statusDescription(502)).isEqualTo("Bad Gateway");
        assertThat(ProviderDiagnostics.statusDescription(401)).isEqualTo("Unauthorized");
        assertThat(ProviderDiagnostics.statusDescription(429)).isEqualTo("Too Many Requests");
        assertThat(ProviderDiagnostics.statusDescription(499)).isEqualTo("HTTP 499");
    }
}
