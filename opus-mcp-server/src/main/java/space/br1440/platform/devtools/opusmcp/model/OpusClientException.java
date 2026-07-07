package space.br1440.platform.devtools.opusmcp.model;

public final class OpusClientException extends Exception {

    public enum Reason {
        MISSING_API_KEY,
        MISSING_BASE_URL,
        MODEL_NOT_ALLOWED,
        TIMEOUT,
        PARSE_ERROR,
        NETWORK_ERROR,
        HTTP_ERROR
    }

    private final Reason reason;
    private final int httpStatus;
    private final ProviderCallMetadata providerMetadata;

    public OpusClientException(Reason reason, String message) {
        this(reason, message, -1, ProviderCallMetadata.notAttempted());
    }

    public OpusClientException(Reason reason, String message, int httpStatus) {
        this(reason, message, httpStatus, ProviderCallMetadata.attemptedWithoutEnvelope());
    }

    public OpusClientException(Reason reason, String message, ProviderCallMetadata providerMetadata) {
        this(reason, message, -1, providerMetadata);
    }

    public OpusClientException(
            Reason reason, String message, int httpStatus, ProviderCallMetadata providerMetadata) {
        super(message);
        this.reason = reason;
        this.httpStatus = httpStatus;
        this.providerMetadata = providerMetadata == null
                ? ProviderCallMetadata.notAttempted()
                : providerMetadata;
    }

    public Reason reason() {
        return reason;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public ProviderCallMetadata providerMetadata() {
        return providerMetadata;
    }
}
