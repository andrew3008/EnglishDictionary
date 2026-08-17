package space.br1440.platform.tracing.runtime.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import space.br1440.platform.tracing.test.arch.ModuleTaxonomyArchRules;

@AnalyzeClasses(
        packages = "space.br1440.platform.tracing.runtime",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class RuntimePolicyBoundaryArchTest {

    @ArchTest
    static final ArchRule runtimePolicyIsLoggingAgnostic =
            ModuleTaxonomyArchRules.RUNTIME_POLICY_LOGGING_AGNOSTIC;

    @ArchTest
    static final ArchRule samplingDoesNotDependOnOpenTelemetryOrSpring =
            ModuleTaxonomyArchRules.CORE_POLICY_PACKAGES_NO_OTEL_OR_SPRING;

    @ArchTest
    static final ArchRule samplingModelRemainsPure =
            ModuleTaxonomyArchRules.SAMPLING_MODEL_IS_PURE;

    @ArchTest
    static final ArchRule productionChainAccessIsRestricted =
            ModuleTaxonomyArchRules.PRODUCTION_CHAIN_ACCESS_RESTRICTED;

    @ArchTest
    static final ArchRule controlImplementationsRemainInRuntimePolicy =
            ModuleTaxonomyArchRules.CONTROL_IMPLS_ONLY_IN_RUNTIME_POLICY;

    @ArchTest
    static final ArchRule controlImplementationAccessIsRestricted =
            ModuleTaxonomyArchRules.CONTROL_IMPL_ACCESS_RESTRICTED;

    @ArchTest
    static final ArchRule versionedStatePrimitiveRemainsInRuntimePolicy =
            ModuleTaxonomyArchRules.VERSIONED_STATE_PRIMITIVE_ONLY_IN_RUNTIME_POLICY;

    @ArchTest
    static final ArchRule remoteServiceTraceContextLivesInRuntimePolicy =
            ModuleTaxonomyArchRules.REMOTE_SERVICE_TRACE_CONTEXT_IN_RUNTIME_POLICY;

    @ArchTest
    static final ArchRule traceMirrorHasSingleLifecycleOwner =
            noClasses().that().doNotHaveSimpleName("RemoteServiceTraceContext")
                    .should().dependOnClassesThat().haveSimpleName("RemoteServiceTraceMirror")
                    .because("the bounded trace mirror must have one lifecycle owner");
}
