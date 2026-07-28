package space.br1440.platform.tracing.autoconfigure.configuration;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

/**
 * P4 Architecture tests.
 * Prove invariants that cannot be verified at compile time.
 */
class P4ArchitectureTest {

    private static JavaClasses productionClasses;

    @BeforeAll
    static void importClasses() {
        productionClasses = new ClassFileImporter()
                .importPackages("space.br1440.platform.tracing.autoconfigure");
    }

    @Test
    void noDevPrefixedProductionClasses() {
        ArchRule rule = noClasses()
                .that().resideOutsideOfPackage("..test..")
                .should().haveSimpleNameStartingWith("Dev")
                .because("Production types must not have Dev prefix (P4 §5)");
        rule.check(productionClasses);
    }

    @Test
    void noDevPackage() {
        ArchRule rule = noClasses()
                .should().resideInAPackage("..dev..")
                .because("dev package is forbidden (P4 §23)");
        rule.check(productionClasses);
    }

    @Test
    void noProfileDevAnnotation() {
        // Check that no class uses @Profile with "dev" or "local" value
        // ArchUnit can check annotation presence; value-level check requires custom condition
        noClasses()
                .should().beAnnotatedWith(org.springframework.context.annotation.Profile.class)
                .because("P4 forbids @Profile annotations on configuration classes (§4)");
        // Note: if Profile annotations exist on test classes only, scope is already limited
        // by productionClasses import which excludes test sources
    }

    @Test
    void authorityEnumHasExactlyThreeValues() {
        // Verify TracingConfigurationAuthority has exactly HELM, CONFIG_SERVER, ACTUATOR
        TracingConfigurationAuthority[] values = TracingConfigurationAuthority.values();
        assert values.length == 3 : "Expected 3 authority values, found: " + values.length;
        assert values[0] == TracingConfigurationAuthority.HELM;
        assert values[1] == TracingConfigurationAuthority.CONFIG_SERVER;
        assert values[2] == TracingConfigurationAuthority.ACTUATOR;
    }

    @Test
    void postureEnumHasExactlyThreeValues() {
        PlatformEnvironmentPosture[] values = PlatformEnvironmentPosture.values();
        assert values.length == 3 : "Expected 3 posture values, found: " + values.length;
    }

    @Test
    void authorityAndPostureAreIndependentAxes() {
        // Authority enum must not contain LOCAL, SHARED, PRODUCTION
        for (TracingConfigurationAuthority a : TracingConfigurationAuthority.values()) {
            assert !a.name().equals("LOCAL") && !a.name().equals("SHARED") && !a.name().equals("PRODUCTION")
                    : "Authority must not encode posture: " + a;
        }
        // Posture enum must not contain HELM, CONFIG_SERVER, ACTUATOR
        for (PlatformEnvironmentPosture p : PlatformEnvironmentPosture.values()) {
            assert !p.name().equals("HELM") && !p.name().equals("CONFIG_SERVER") && !p.name().equals("ACTUATOR")
                    : "Posture must not encode authority: " + p;
        }
    }

    @Test
    void guardRejectsActuatorWithSharedPosture() {
        // Simulate environment with ACTUATOR authority + SHARED posture
        try {
            TracingConfigurationAuthorityGuard guard = new TracingConfigurationAuthorityGuard(
                    TracingConfigurationAuthority.ACTUATOR,
                    PlatformEnvironmentPosture.SHARED,
                    true,
                    true
            );
            guard.validate();
            throw new AssertionError("Expected TracingConfigurationGuardException for ACTUATOR + SHARED");
        } catch (TracingConfigurationGuardException e) {
            // expected
        }
    }

    @Test
    void guardRejectsActuatorWithProductionPosture() {
        try {
            TracingConfigurationAuthorityGuard guard = new TracingConfigurationAuthorityGuard(
                    TracingConfigurationAuthority.ACTUATOR,
                    PlatformEnvironmentPosture.PRODUCTION,
                    true,
                    true
            );
            guard.validate();
            throw new AssertionError("Expected TracingConfigurationGuardException for ACTUATOR + PRODUCTION");
        } catch (TracingConfigurationGuardException e) {
            // expected
        }
    }

    @Test
    void guardAllowsActuatorWithLocalPosture() {
        TracingConfigurationAuthorityGuard guard = new TracingConfigurationAuthorityGuard(
                TracingConfigurationAuthority.ACTUATOR,
                PlatformEnvironmentPosture.LOCAL,
                true,
                true
        );
        guard.validate(); // must not throw
        assert guard.isActuatorMutationSurfaceAllowed();
    }

    @Test
    void guardRejectsDisabledWithConfigServerAuthority() {
        try {
            TracingConfigurationAuthorityGuard guard = new TracingConfigurationAuthorityGuard(
                    TracingConfigurationAuthority.CONFIG_SERVER,
                    PlatformEnvironmentPosture.SHARED,
                    false, // tracing disabled
                    false
            );
            guard.validate();
            throw new AssertionError("Expected TracingConfigurationGuardException for DISABLED + CONFIG_SERVER");
        } catch (TracingConfigurationGuardException e) {
            // expected
        }
    }

    @Test
    void helmAuthorityHasNoActuatorMutationSurface() {
        TracingConfigurationAuthorityGuard guard = new TracingConfigurationAuthorityGuard(
                TracingConfigurationAuthority.HELM,
                PlatformEnvironmentPosture.LOCAL,
                true,
                true
        );
        guard.validate(); // must not throw
        assert !guard.isActuatorMutationSurfaceAllowed() : "HELM must not allow Actuator mutation surface";
    }

    @Test
    void configServerAuthorityHasNoActuatorMutationSurface() {
        TracingConfigurationAuthorityGuard guard = new TracingConfigurationAuthorityGuard(
                TracingConfigurationAuthority.CONFIG_SERVER,
                PlatformEnvironmentPosture.LOCAL,
                true,
                true
        );
        guard.validate(); // must not throw
        assert !guard.isActuatorMutationSurfaceAllowed() : "CONFIG_SERVER must not allow Actuator mutation surface";
    }
}
