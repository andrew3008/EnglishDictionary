package space.br1440.platform.tracing.test.arch;

import com.tngtech.archunit.lang.ArchRule;
import lombok.experimental.UtilityClass;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * PR-4 architecture fitness functions — consolidated ArchUnit rules.
 *
 * @see docs/architecture/platform-tracing-fitness-functions-implementation.md
 */
@UtilityClass
public final class ArchitectureFitnessArchRules {

    private static final String PROTOCOL_PACKAGE = "space.br1440.platform.tracing.api.control.protocol..";

    /**
     * FF-01: {@code api.control.protocol} must remain JDK-only (no Spring/OTel/Jackson/Micrometer/YAML).
     */
    public static final ArchRule API_PROTOCOL_PACKAGE_JDK_ONLY = noClasses()
            .that().resideInAPackage(PROTOCOL_PACKAGE)
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..",
                    "io.opentelemetry..",
                    "com.fasterxml.jackson..",
                    "io.micrometer..",
                    "org.yaml.snakeyaml..")
            .because("platform-tracing-api control protocol must stay JDK-only for cross-classloader JMX contract");

    /**
     * FF-02: control protocol in {@code platform-tracing-api} must not depend on internal implementation modules.
     */
    public static final ArchRule API_PROTOCOL_NO_IMPLEMENTATION_MODULES = noClasses()
            .that().resideInAPackage(PROTOCOL_PACKAGE)
            .should().dependOnClassesThat().resideInAnyPackage(
                    "space.br1440.platform.tracing.core..",
                    "space.br1440.platform.tracing.otel.javaagent..",
                    "space.br1440.platform.tracing.autoconfigure..")
            .because("control protocol in api must not reference core/otel-extension/autoconfigure implementation types");

    public static final ArchRule API_PROTOCOL_NO_OPENMBEAN = noClasses()
            .that().resideInAPackage(PROTOCOL_PACKAGE)
            .should().dependOnClassesThat().resideInAnyPackage("javax.management.openmbean..")
            .because("OpenMBean types belong outside platform-tracing-api");

    /**
     * All public top-level production types in protocol package use unified {@code TracingControlProtocol*} prefix.
     */
    public static final ArchRule PROTOCOL_API_TYPES_USE_UNIFIED_PREFIX = classes()
            .that().resideInAPackage(PROTOCOL_PACKAGE)
            .and().arePublic()
            .and().areTopLevelClasses()
            .should().haveSimpleNameStartingWith("TracingControlProtocol")
            .because("public protocol API types must use unified TracingControlProtocol prefix");

    /**
     * No {@code Wire} in public top-level production type names under protocol package.
     */
    public static final ArchRule PROTOCOL_API_TYPES_DO_NOT_USE_WIRE_NAMING = classes()
            .that().resideInAPackage(PROTOCOL_PACKAGE)
            .and().arePublic()
            .and().areTopLevelClasses()
            .should().haveSimpleNameNotContaining("Wire")
            .because("Wire must not appear in production protocol API type names");

    /**
     * FF-09a: production autoconfigure must not depend on protocol validator (Map wire not production path).
     */
    public static final ArchRule PRODUCTION_AUTOCONFIGURE_NO_WIRE_VALIDATOR = noClasses()
            .that().resideInAPackage("space.br1440.platform.tracing.autoconfigure..")
            .and().resideOutsideOfPackage("..test..")
            .and().resideOutsideOfPackage("..tests..")
            .should().dependOnClassesThat().haveSimpleName("TracingControlProtocolValidator")
            .allowEmptyShould(true)
            .because("RuntimeConfigApplier/Actuator production path must not use Map wire validator yet");
}
