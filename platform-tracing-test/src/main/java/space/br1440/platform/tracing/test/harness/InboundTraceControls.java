package space.br1440.platform.tracing.test.harness;

import jakarta.annotation.Nullable;
import lombok.experimental.UtilityClass;
import space.br1440.platform.tracing.api.propagation.control.InboundTraceControl;

/**
 * Фабрика для прямого построения {@link InboundTraceControl} в unit/integration-тестах.
 * <p>
 * Не требует зависимости от {@code platform-tracing-core}.
 * Для тестов парсинга заголовков используйте {@code DefaultInboundTraceControlExtractor} напрямую.
 */
@UtilityClass
public final class InboundTraceControls {

    /**
     * Создаёт {@link InboundTraceControl} с основными полями. {@code samplingReason}
     * и {@code rawForceTraceValue} устанавливаются в {@code null}.
     */
    public static InboundTraceControl of(
            boolean forceTrace,
            boolean qaTrace,
            @Nullable String requestId) {
        return of(forceTrace, qaTrace, requestId, null, null);
    }

    /**
     * Создаёт {@link InboundTraceControl} со всеми полями.
     */
    public static InboundTraceControl of(
            boolean forceTrace,
            boolean qaTrace,
            @Nullable String requestId,
            @Nullable String samplingReason,
            @Nullable String rawForceTraceValue) {
        return new InboundTraceControl(
                forceTrace, qaTrace, requestId, samplingReason, rawForceTraceValue);
    }
}
