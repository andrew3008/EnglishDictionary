package space.br1440.platform.tracing.otel.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import space.br1440.platform.tracing.test.arch.ModuleTaxonomyArchRules;

@AnalyzeClasses(
        packages = "space.br1440.platform.tracing.otel",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class CorePolicyBoundaryArchTest {

    @ArchTest
    static final ArchRule corePolicyDoesNotDependOnOpenTelemetryOrSpring =
            ModuleTaxonomyArchRules.CORE_POLICY_PACKAGES_NO_OTEL_OR_SPRING;

    @ArchTest
    static final ArchRule coreDoesNotDependOnJmx =
            ModuleTaxonomyArchRules.CORE_MAIN_NO_JMX;

    @ArchTest
    static final ArchRule samplingModelRemainsPure =
            ModuleTaxonomyArchRules.SAMPLING_MODEL_IS_PURE;

    @ArchTest
    static final ArchRule productionChainAccessIsRestricted =
            ModuleTaxonomyArchRules.PRODUCTION_CHAIN_ACCESS_RESTRICTED;

    @ArchTest
    static final ArchRule controlImplementationsRemainInCore =
            ModuleTaxonomyArchRules.CONTROL_IMPLS_ONLY_IN_CORE;

    @ArchTest
    static final ArchRule controlImplementationAccessIsRestricted =
            ModuleTaxonomyArchRules.CONTROL_IMPL_ACCESS_RESTRICTED;

    @ArchTest
    static final ArchRule versionedStatePrimitiveRemainsInCore =
            ModuleTaxonomyArchRules.VERSIONED_STATE_PRIMITIVE_ONLY_IN_CORE;

    @ArchTest
    static final ArchRule remoteServiceMdcImplementationsRemainInCore =
            ModuleTaxonomyArchRules.REMOTE_SERVICE_MDC_IMPL_ONLY_IN_CORE;

    @ArchTest
    static final ArchRule traceMirrorHasSingleLifecycleOwner =
            noClasses().that().doNotHaveSimpleName("RemoteServiceTraceContext")
                    .should().dependOnClassesThat().haveSimpleName("RemoteServiceTraceMirror")
                    .because("the bounded trace mirror must have one lifecycle owner");
}
