package space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime;

public final class SanitizerBootstrapException extends IllegalStateException {

    public static final String RULE_SET_EMPTY = "SANITIZER_RULE_SET_EMPTY";
    public static final String HMAC_INITIALIZATION_FAILED = "SANITIZER_HMAC_INITIALIZATION_FAILED";
    public static final String RULE_LOADING_FAILED = "SANITIZER_RULE_LOADING_FAILED";
    public static final String RULE_METADATA_INVALID = "SANITIZER_RULE_METADATA_INVALID";
    public static final String FOREIGN_SAFE_EXPORTER = "SANITIZER_FOREIGN_SAFE_EXPORTER";
    public static final String TRACE_PIPELINE_ATTESTATION_FAILED = "TRACE_PIPELINE_ATTESTATION_FAILED";

    private final String code;
    private final String propertyName;

    public SanitizerBootstrapException(String code, String message) {
        this(code, null, message, null);
    }

    public SanitizerBootstrapException(String code, String message, Throwable cause) {
        this(code, null, message, cause);
    }

    public SanitizerBootstrapException(String code, String propertyName, String message) {
        this(code, propertyName, message, null);
    }

    public SanitizerBootstrapException(String code, String propertyName, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.propertyName = propertyName;
    }

    public String code() {
        return code;
    }

    public String propertyName() {
        return propertyName;
    }
}
