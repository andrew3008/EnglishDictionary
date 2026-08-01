package space.br1440.platform.tracing.test.arch;

import com.tngtech.archunit.lang.ArchRule;
import lombok.experimental.UtilityClass;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * PR-4 architecture fitness functions — consolidated ArchUnit rules.
 *
 * @see docs/architecture/platform-tracing-fitness-functions-implementation.md
 */
@UtilityClass
public final class ArchitectureFitnessArchRules {

    /**
     * FF-09a: production autoconfigure must not depend on the retired Map-wire protocol validator.
     */
    public static final ArchRule PRODUCTION_AUTOCONFIGURE_NO_WIRE_VALIDATOR = noClasses()
            .that().resideInAPackage("space.br1440.platform.tracing.autoconfigure..")
            .and().resideOutsideOfPackage("..test..")
            .and().resideOutsideOfPackage("..tests..")
            .should().dependOnClassesThat().haveSimpleName("TracingControlProtocolValidator")
            .because("Actuator production path must not use Map wire validator yet");
}
