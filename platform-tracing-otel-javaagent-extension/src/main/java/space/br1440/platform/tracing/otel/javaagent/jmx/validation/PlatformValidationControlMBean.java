package space.br1440.platform.tracing.otel.javaagent.jmx.validation;

/**
 * P6.6: platform Validation MBean is read-only. Mutations go through private {@code applySnapshot}.
 */
@SuppressWarnings("unused")
public interface PlatformValidationControlMBean {

    boolean isValidationEnabled();

    boolean isValidationStrict();

    boolean isValidationStrictRuntimeAllowed();

    long getValidationConfigVersion();

    String getValidationConfigLastUpdatedSource();
}
