package space.br1440.platform.tracing.otel.javaagent.jmx.validation;

import lombok.RequiredArgsConstructor;
import space.br1440.platform.tracing.otel.javaagent.processor.ValidatingSpanProcessor;

@RequiredArgsConstructor
public final class PlatformValidationControl implements PlatformValidationControlMBean {

    private final ValidatingSpanProcessor validating;

    @Override
    public boolean isValidationEnabled() {
        return (validating != null) && validating.isEnabled();
    }

    @Override
    public boolean isValidationStrict() {
        return (validating != null) && validating.isStrict();
    }

    @Override
    public boolean isValidationStrictRuntimeAllowed() {
        return (validating != null) && validating.isStrictRuntimeAllowed();
    }

    @Override
    public long getValidationConfigVersion() {
        return (validating != null) ? validating.getPolicyVersion() : -1L;
    }

    @Override
    public String getValidationConfigLastUpdatedSource() {
        return (validating != null) ? validating.getPolicySource() : "unknown";
    }
}
