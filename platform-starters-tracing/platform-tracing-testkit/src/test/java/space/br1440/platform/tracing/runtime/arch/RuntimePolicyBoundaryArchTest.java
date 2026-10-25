package space.br1440.platform.tracing.runtime.arch;

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
    @SuppressWarnings("unused")
    static final ArchRule runtimePolicyIsLoggingAgnostic =
            ModuleTaxonomyArchRules.RUNTIME_POLICY_LOGGING_AGNOSTIC;

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule samplingDoesNotDependOnOpenTelemetryOrSpring =
            ModuleTaxonomyArchRules.CORE_POLICY_PACKAGES_NO_OTEL_OR_SPRING;

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule samplingModelRemainsPure =
            ModuleTaxonomyArchRules.SAMPLING_MODEL_IS_PURE;

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule productionChainAccessIsRestricted =
            ModuleTaxonomyArchRules.PRODUCTION_CHAIN_ACCESS_RESTRICTED;

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule controlImplementationsRemainInRuntimePolicy =
            ModuleTaxonomyArchRules.CONTROL_IMPLS_ONLY_IN_RUNTIME_POLICY;

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule controlImplementationAccessIsRestricted =
            ModuleTaxonomyArchRules.CONTROL_IMPL_ACCESS_RESTRICTED;

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule outboundPropagationDoesNotReadTraceControlRequestId =
            ModuleTaxonomyArchRules.OUTBOUND_DOES_NOT_READ_INBOUND_TRACE_CONTROL_REQUEST_ID;
}
