package space.br1440.platform.tracing.otel.javaagent.jmx.validation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import space.br1440.platform.tracing.otel.javaagent.jmx.support.JmxConfigReloadRecorder;
import space.br1440.platform.tracing.otel.javaagent.processor.ValidatingSpanProcessor;

import java.util.concurrent.atomic.LongAdder;

@Slf4j
@RequiredArgsConstructor
public final class PlatformValidationControl implements PlatformValidationControlMBean {

    private final ValidatingSpanProcessor validating;
    private final LongAdder invalidConfigCounter;

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
    public void updateValidationPolicy(boolean enabled, boolean strict) {
        updateValidationPolicy(enabled, strict, "JMX");
    }

    @Override
    public void updateValidationPolicy(boolean enabled, boolean strict, String source) {
        if (validating == null) {
            throw new IllegalStateException("ValidatingSpanProcessor is not registered");
        }

        boolean applied = validating.tryApplyPolicyUpdate(enabled, strict, source);
        if (!applied) {
            invalidConfigCounter.increment();
            JmxConfigReloadRecorder.record("validation", false, validating.getPolicyVersion());
            log.warn("""
                       Validation policy update rejected (source={}). Version unchanged: {}, enabled={}, strict={} \
                       (live enabled={}, live strict={})\
                     """,
                    source, validating.getPolicyVersion(), enabled, strict,
                    validating.isEnabled(), validating.isStrict());
            return;
        }

        JmxConfigReloadRecorder.record("validation", true, validating.getPolicyVersion());
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
