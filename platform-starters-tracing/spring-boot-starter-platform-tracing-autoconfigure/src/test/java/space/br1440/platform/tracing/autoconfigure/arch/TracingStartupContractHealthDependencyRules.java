package space.br1440.platform.tracing.autoconfigure.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.lang.ArchRule;

final class TracingStartupContractHealthDependencyRules {

    static final String STARTUP_PACKAGE = "..autoconfigure.diagnostics.health.startup..";

    static final String PRODUCTION_AUTOCONFIG_FQCN =
            "space.br1440.platform.tracing.autoconfigure.diagnostics.health.startup."
                    + "TracingStartupContractHealthAutoConfiguration";

    private TracingStartupContractHealthDependencyRules() {
    }

    static ArchRule coreDependencyBoundary(String autoconfigFqcn) {
        return classes()
                .that().resideInAPackage(STARTUP_PACKAGE)
                .and().doNotHaveFullyQualifiedName(autoconfigFqcn)
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "java..",
                        "lombok..",
                        "org.springframework.boot.actuate.health..",
                        STARTUP_PACKAGE);
    }

    /** Rule B: единственная adapter-точка ограничена узким allowlist'ом Spring wiring + platform runtime. */
    static ArchRule autoConfigurationDependencyBoundary(String autoconfigFqcn) {
        return classes()
                .that().haveFullyQualifiedName(autoconfigFqcn)
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "java..",
                        "org.springframework.context.annotation..",
                        "org.springframework.boot.autoconfigure..",
                        "org.springframework.boot.actuate.autoconfigure.health..",
                        "org.springframework.boot.actuate.health..",
                        "space.br1440.platform.tracing.autoconfigure",
                        "space.br1440.platform.tracing.autoconfigure.agent..",
                        "space.br1440.platform.tracing.otel.runtime..",
                        STARTUP_PACKAGE);
    }

    /** Rule C: весь пакет (включая AutoConfiguration) не видит OTel SDK/exporter/JMX/блокирующие клиенты. */
    static ArchRule forbiddenTransportAndSdkDependencies() {
        return noClasses()
                .that().resideInAPackage(STARTUP_PACKAGE)
                .should().dependOnClassesThat().resideInAnyPackage(
                        "io.opentelemetry.sdk..",
                        "io.opentelemetry.exporter..",
                        "io.grpc..",
                        "space.br1440.platform.tracing.autoconfigure.diagnostics.jmx..")
                .orShould().dependOnClassesThat().haveFullyQualifiedName(
                        "org.springframework.web.client.RestTemplate")
                .orShould().dependOnClassesThat().haveFullyQualifiedName(
                        "org.springframework.web.reactive.function.client.WebClient");
    }

    /** Rule D: startup-ядро не образует обратное ребро на единственную adapter-точку. */
    static ArchRule coreMustNotDependOnAutoConfiguration(String autoconfigFqcn) {
        return noClasses()
                .that().resideInAPackage(STARTUP_PACKAGE)
                .and().doNotHaveFullyQualifiedName(autoconfigFqcn)
                .should().dependOnClassesThat().haveFullyQualifiedName(autoconfigFqcn);
    }
}
