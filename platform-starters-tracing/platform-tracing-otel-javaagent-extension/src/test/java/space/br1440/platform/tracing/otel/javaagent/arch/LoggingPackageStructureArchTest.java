package space.br1440.platform.tracing.otel.javaagent.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import space.br1440.platform.tracing.otel.javaagent.logging.RateLimitedLogger;
import space.br1440.platform.tracing.otel.javaagent.logging.ratelimit.SeverityThrottle;

@AnalyzeClasses(
        packages = "space.br1440.platform.tracing.otel.javaagent",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class LoggingPackageStructureArchTest {

    private static final String LOGGING = "space.br1440.platform.tracing.otel.javaagent.logging";

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule logging_layers = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("Facade").definedBy(LOGGING)
            .layer("Event").definedBy(LOGGING + ".event..")
            .layer("RateLimit").definedBy(LOGGING + ".ratelimit..")
            .layer("Schema").definedBy(LOGGING + ".schema..")
            .whereLayer("Facade").mayNotBeAccessedByAnyLayer()
            .whereLayer("Event").mayOnlyBeAccessedByLayers("Facade")
            .whereLayer("RateLimit").mayOnlyBeAccessedByLayers("Facade")
            .whereLayer("Schema").mayOnlyBeAccessedByLayers("Facade", "Event");

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule ratelimit_is_a_pure_primitive = classes()
            .that().resideInAPackage(LOGGING + ".ratelimit..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(LOGGING + ".ratelimit..", "java..");

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule severity_throttle_is_owned_by_rate_limited_logger = classes()
            .that().belongToAnyOf(SeverityThrottle.class)
            .should().onlyHaveDependentClassesThat()
            .belongToAnyOf(SeverityThrottle.class, RateLimitedLogger.class)
            .because("SeverityThrottle is public only because subpackages do not share package-private access");
}
