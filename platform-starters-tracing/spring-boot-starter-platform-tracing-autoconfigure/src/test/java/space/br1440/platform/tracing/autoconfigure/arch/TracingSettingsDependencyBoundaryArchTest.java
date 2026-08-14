package space.br1440.platform.tracing.autoconfigure.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Защищает разделение read- и delivery-контрактов управления tracing settings.
 */
@AnalyzeClasses(
        packages = "space.br1440.platform.tracing.autoconfigure.control.settings",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class TracingSettingsDependencyBoundaryArchTest {

    private static final String SETTINGS_PACKAGE =
            "space.br1440.platform.tracing.autoconfigure.control.settings";
    private static final String READ_PACKAGE = SETTINGS_PACKAGE + ".read..";
    private static final String DELIVERY_PACKAGE = SETTINGS_PACKAGE + ".delivery..";

    @ArchTest
    static final ArchRule settingsRootMustNotDependOnChildContracts = noClasses()
            .that().resideInAPackage(SETTINGS_PACKAGE)
            .should().dependOnClassesThat().resideInAnyPackage(READ_PACKAGE, DELIVERY_PACKAGE)
            .because("корневой пакет содержит общие immutable contracts для read и delivery");

    @ArchTest
    static final ArchRule readContractsMustNotDependOnDeliveryContracts = noClasses()
            .that().resideInAPackage(READ_PACKAGE)
            .should().dependOnClassesThat().resideInAPackage(DELIVERY_PACKAGE)
            .because("read-контракты не должны зависеть от write path");

    @ArchTest
    static final ArchRule deliveryContractsMustNotDependOnReadContracts = noClasses()
            .that().resideInAPackage(DELIVERY_PACKAGE)
            .should().dependOnClassesThat().resideInAPackage(READ_PACKAGE)
            .because("delivery-контракты не должны зависеть от read-моделей");
}
