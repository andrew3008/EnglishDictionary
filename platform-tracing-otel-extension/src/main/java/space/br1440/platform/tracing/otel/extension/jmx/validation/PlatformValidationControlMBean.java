package space.br1440.platform.tracing.otel.extension.jmx.validation;

@SuppressWarnings("unused")
public interface PlatformValidationControlMBean {

    boolean isValidationEnabled();

    boolean isValidationStrict();

    boolean isValidationStrictRuntimeAllowed();

    void updateValidationPolicy(boolean enabled, boolean strict);

    void updateValidationPolicy(boolean enabled, boolean strict, String source);

    long getValidationConfigVersion();

    String getValidationConfigLastUpdatedSource();

}
