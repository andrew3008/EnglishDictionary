package space.br1440.platform.tracing.autoconfigure.diagnostics.health.startup.archfixture.invalid;

import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.PlatformTracingJmxClient;

/**
 * Негативная фикстура Rule B: имитирует единственную adapter-точку пакета, но зависит от
 * {@code diagnostics.jmx}, который не входит в allowlist {@code TracingStartupContractHealthAutoConfiguration}.
 * FQCN этого класса подставляется в {@link space.br1440.platform.tracing.autoconfigure.arch
 * .TracingStartupContractHealthDependencyRules#autoConfigurationDependencyBoundary(String)} вместо
 * production FQCN, чтобы доказать, что правило действительно ловит нарушение allowlist, а не только
 * молча проходит на пустой выборке.
 */
public final class InvalidAutoConfigurationDependingOnJmxFacade {

    private final PlatformTracingJmxClient client;

    public InvalidAutoConfigurationDependingOnJmxFacade(PlatformTracingJmxClient client) {
        this.client = client;
    }
}
