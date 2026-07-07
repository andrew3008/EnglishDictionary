package space.br1440.platform.tracing.api.control.protocol.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import space.br1440.platform.tracing.test.arch.ArchitectureFitnessArchRules;

/**
 * PR-4 FF-01 / FF-02 / naming: {@code api.control.protocol} JDK-only and unified type naming.
 */
@AnalyzeClasses(
        packages = "space.br1440.platform.tracing.api.control.protocol",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class ApiProtocolPackagePurityArchTest {

    @ArchTest
    static final ArchRule apiProtocolPackageJdkOnly = ArchitectureFitnessArchRules.API_PROTOCOL_PACKAGE_JDK_ONLY;

    @ArchTest
    static final ArchRule apiProtocolNoImplementationModules =
            ArchitectureFitnessArchRules.API_PROTOCOL_NO_IMPLEMENTATION_MODULES;

    @ArchTest
    static final ArchRule apiProtocolNoOpenMBean = ArchitectureFitnessArchRules.API_PROTOCOL_NO_OPENMBEAN;

    @ArchTest
    static final ArchRule protocolApiTypesUseUnifiedPrefix =
            ArchitectureFitnessArchRules.PROTOCOL_API_TYPES_USE_UNIFIED_PREFIX;

    @ArchTest
    static final ArchRule protocolApiTypesDoNotUseWireNaming =
            ArchitectureFitnessArchRules.PROTOCOL_API_TYPES_DO_NOT_USE_WIRE_NAMING;
}
