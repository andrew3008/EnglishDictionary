package space.br1440.platform.tracing.test.harness;

import jakarta.annotation.Nullable;
import space.br1440.platform.tracing.api.propagation.control.InboundTraceControl;

/**
 * Фабрика {@link InboundTraceControl} для unit/integration-тестов.
 * <p>
 * Используйте вместо прямого {@code new InboundTraceControl(...)}, чтобы тест не зависел
 * от порядка и количества полей record.
 * <p>
 * Для тестов <b>парсинга</b> заголовков используйте {@code new DefaultInboundTraceControlExtractor().fromHeaders(...)}.
 */
public final class InboundTraceControls {

    private InboundTraceControls() {
    }

    /**
     * Создаёт {@link InboundTraceControl} без знания о samplingReason и rawForceTraceValue.
     * Используйте в сценариях, где предмет теста — не парсинг заголовков.
     */
    public static InboundTraceControl of(
            boolean forceTrace,
            boolean qaTrace,
            @Nullable String requestId) {
        return of(forceTrace, qaTrace, requestId, null, null);
    }

    /**
     * Полный конструктор со всеми полями.
     * Используйте в сценариях, где samplingReason/rawForceTraceValue важны для проверки.
     */
    public static InboundTraceControl of(
            boolean forceTrace,
            boolean qaTrace,
            @Nullable String requestId,
            @Nullable String samplingReason,
            @Nullable String rawForceTraceValue) {
        return new InboundTraceControl(forceTrace, qaTrace, requestId, samplingReason, rawForceTraceValue);
    }
}
