package space.br1440.platform.devtools.opusmcp.audit;

/**
 * Metadata-only audit record. Intentionally contains no task, context, constraints, model output,
 * API key, or raw provider response.
 */
public record AuditRecord(
        String requestId,
        String timestamp,
        String toolName,
        String model,
        String language,
        String outputFormat,
        String riskLevel,
        String status,
        long latencyMs,
        long inputCharCount,
        long estimatedInputTokens,
        long estimatedOutputTokens,
        double estimatedCost,
        String budgetDecision,
        String rateLimitDecision,
        String httpStatusCategory,
        String providerRequestId,
        String envelopeKind,
        String diagnosticCategory,
        boolean providerCallAttempted) {

    public static Builder builder() {
        return new Builder();
    }

    /** Renders a single-line, secret-free key=value string for structured logging. */
    public String toLogString() {
        return "requestId=" + n(requestId)
                + " timestamp=" + n(timestamp)
                + " tool=" + n(toolName)
                + " model=" + n(model)
                + " language=" + n(language)
                + " outputFormat=" + n(outputFormat)
                + " riskLevel=" + n(riskLevel)
                + " status=" + n(status)
                + " latencyMs=" + latencyMs
                + " inputCharCount=" + inputCharCount
                + " estimatedInputTokens=" + estimatedInputTokens
                + " estimatedOutputTokens=" + estimatedOutputTokens
                + " estimatedCost=" + estimatedCost
                + " budgetDecision=" + n(budgetDecision)
                + " rateLimitDecision=" + n(rateLimitDecision)
                + " httpStatusCategory=" + n(httpStatusCategory)
                + " providerRequestId=" + n(providerRequestId)
                + " envelopeKind=" + n(envelopeKind)
                + " diagnosticCategory=" + n(diagnosticCategory)
                + " providerCallAttempted=" + providerCallAttempted;
    }

    private static String n(String value) {
        return value == null ? "-" : value;
    }

    public static final class Builder {
        private String requestId;
        private String timestamp;
        private String toolName;
        private String model;
        private String language;
        private String outputFormat;
        private String riskLevel;
        private String status;
        private long latencyMs;
        private long inputCharCount;
        private long estimatedInputTokens;
        private long estimatedOutputTokens;
        private double estimatedCost;
        private String budgetDecision;
        private String rateLimitDecision;
        private String httpStatusCategory;
        private String providerRequestId;
        private String envelopeKind;
        private String diagnosticCategory;
        private boolean providerCallAttempted;

        public Builder requestId(String v) {
            this.requestId = v;
            return this;
        }

        public Builder timestamp(String v) {
            this.timestamp = v;
            return this;
        }

        public Builder toolName(String v) {
            this.toolName = v;
            return this;
        }

        public Builder model(String v) {
            this.model = v;
            return this;
        }

        public Builder language(String v) {
            this.language = v;
            return this;
        }

        public Builder outputFormat(String v) {
            this.outputFormat = v;
            return this;
        }

        public Builder riskLevel(String v) {
            this.riskLevel = v;
            return this;
        }

        public Builder status(String v) {
            this.status = v;
            return this;
        }

        public Builder latencyMs(long v) {
            this.latencyMs = v;
            return this;
        }

        public Builder inputCharCount(long v) {
            this.inputCharCount = v;
            return this;
        }

        public Builder estimatedInputTokens(long v) {
            this.estimatedInputTokens = v;
            return this;
        }

        public Builder estimatedOutputTokens(long v) {
            this.estimatedOutputTokens = v;
            return this;
        }

        public Builder estimatedCost(double v) {
            this.estimatedCost = v;
            return this;
        }

        public Builder budgetDecision(String v) {
            this.budgetDecision = v;
            return this;
        }

        public Builder rateLimitDecision(String v) {
            this.rateLimitDecision = v;
            return this;
        }

        public Builder httpStatusCategory(String v) {
            this.httpStatusCategory = v;
            return this;
        }

        public Builder providerRequestId(String v) {
            this.providerRequestId = v;
            return this;
        }

        public Builder envelopeKind(String v) {
            this.envelopeKind = v;
            return this;
        }

        public Builder diagnosticCategory(String v) {
            this.diagnosticCategory = v;
            return this;
        }

        public Builder providerCallAttempted(boolean v) {
            this.providerCallAttempted = v;
            return this;
        }

        public AuditRecord build() {
            return new AuditRecord(
                    requestId, timestamp, toolName, model, language, outputFormat, riskLevel,
                    status, latencyMs, inputCharCount, estimatedInputTokens, estimatedOutputTokens,
                    estimatedCost, budgetDecision, rateLimitDecision, httpStatusCategory,
                    providerRequestId, envelopeKind, diagnosticCategory, providerCallAttempted);
        }
    }
}
