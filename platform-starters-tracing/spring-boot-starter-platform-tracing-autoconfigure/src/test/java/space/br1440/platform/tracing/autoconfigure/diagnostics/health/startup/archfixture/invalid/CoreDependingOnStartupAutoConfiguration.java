package space.br1440.platform.tracing.autoconfigure.diagnostics.health.startup.archfixture.invalid;

import space.br1440.platform.tracing.autoconfigure.diagnostics.health.startup.TracingStartupContractHealthAutoConfiguration;

/**
 * Негативная фикстура Rule D: имитирует startup-ядро, образующее обратное ребро на единственную
 * adapter-точку пакета. Компилируется только благодаря публичности production-класса
 * {@link TracingStartupContractHealthAutoConfiguration}; проходит Rule A (целевой класс лежит в
 * разрешённом собственном пакете) и нарушает только Rule D.
 */
final class CoreDependingOnStartupAutoConfiguration {

    private TracingStartupContractHealthAutoConfiguration configuration;
}
