package space.br1440.platform.tracing.violations;

/**
 * Синтетический нарушитель Rule 3 для self-test {@link space.br1440.platform.tracing.test.arch.OtelDirectIntegrationRulesTest}.
 * Не используется в production-коде.
 */
public interface SpanProcessor {
    void process();
}
