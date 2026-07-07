package space.br1440.platform.devtools.opusmcp.perplexity;

import space.br1440.platform.devtools.opusmcp.smoke.ProviderDiagnostics;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;

/**
 * Phase 8A: ISOLATED Perplexity endpoint compatibility smoke check (manual entrypoint).
 *
 * <p>This is NOT an MCP tool and is NOT wired into the MCP server. It proves only that the configured
 * Perplexity-compatible {@code /chat/completions} endpoint is reachable and returns an OpenAI-style
 * structured response, as groundwork for a possible future read-only research tool (Phase 8B).
 *
 * <p>It sends ONLY a fixed synthetic prompt — never repository context, never code, never secrets —
 * and never prints the API key. Output goes to stdout/stderr for manual operator inspection only
 * (this class is standalone and never participates in MCP stdio JSON-RPC).
 */
public final class PerplexityEndpointSmokeCheck {

    /** The ONLY prompt this check ever sends. No repository content, ever. */
    public static final String SYNTHETIC_PROMPT = "Reply with exactly: OK";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);

    private PerplexityEndpointSmokeCheck() {
    }

    public static void main(String[] args) {
        PerplexityConfig config = PerplexityConfig.fromEnv();
        PerplexityHttpClient client = new PerplexityHttpClient();

        Optional<String> configError = config.validate();
        if (configError.isPresent()) {
            System.err.println("[pplx-smoke] Cannot run Perplexity endpoint check: " + configError.get());
            System.err.println("[pplx-smoke] Config: " + config);
            System.exit(2);
            return;
        }

        System.out.println("[pplx-smoke] Running Perplexity endpoint compatibility check (Phase 8A spike)");
        System.out.println("[pplx-smoke] baseUrl=" + config.baseUrl());
        System.out.println("[pplx-smoke] model=" + config.model());
        System.out.println("[pplx-smoke] Synthetic prompt only (no repository context): \""
                + SYNTHETIC_PROMPT + "\"");

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        PerplexityHttpClient.PerplexityResult result = client.run(config, SYNTHETIC_PROMPT, httpClient);

        System.out.println("[pplx-smoke] ok=" + result.ok()
                + " status=" + result.statusCode()
                + " statusDescription=" + emptyIfNull(result.statusDescription())
                + " model=" + emptyIfNull(result.model())
                + " requestId=" + emptyIfNull(result.requestId())
                + " text=" + (result.extractedText() == null ? "<none>" : '"' + previewText(result.extractedText()) + '"'));
        if (!result.ok()) {
            System.out.println("[pplx-smoke] errorBodyPreview="
                    + (result.errorBodyPreview() == null ? ProviderDiagnostics.EMPTY_BODY : result.errorBodyPreview()));
        }
        System.out.println("[pplx-smoke] diagnosticCategory="
                + (result.diagnosticCategory() == null
                        ? PerplexityDiagnosticCategory.UNKNOWN_PROVIDER_ERROR : result.diagnosticCategory()));
        System.out.println("[pplx-smoke] " + result.message());
        System.exit(result.ok() ? 0 : 1);
    }

    /** Caps the printed response preview so a verbose model answer cannot flood the console. */
    private static String previewText(String text) {
        String collapsed = text.replaceAll("\\s+", " ").strip();
        int cap = 200;
        return collapsed.length() > cap ? collapsed.substring(0, cap) + "...[truncated]" : collapsed;
    }

    private static String emptyIfNull(String value) {
        return value == null ? "" : value;
    }
}
