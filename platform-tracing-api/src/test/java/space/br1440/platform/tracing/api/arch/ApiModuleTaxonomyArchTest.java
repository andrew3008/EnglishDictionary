package space.br1440.platform.tracing.api.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import space.br1440.platform.tracing.test.arch.ModuleTaxonomyArchRules;

/**
 * ArchUnit guardrails для {@code platform-tracing-api}: запрет legacy-пакетов и taxonomy propagation.
 */
@AnalyzeClasses(
        packages = "space.br1440.platform.tracing.api",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class ApiModuleTaxonomyArchTest {

    @ArchTest
    static final ArchRule noApiConfigPackage = ModuleTaxonomyArchRules.NO_API_CONFIG_PACKAGE;

    @ArchTest
    static final ArchRule noApiRuntimeStatePackage = ModuleTaxonomyArchRules.NO_API_RUNTIME_STATE_PACKAGE;

    @ArchTest
    static final ArchRule noApiManualPackage = ModuleTaxonomyArchRules.NO_API_MANUAL_PACKAGE;

    @ArchTest
    static final ArchRule apiPropagationHasNoPublicParsers =
            ModuleTaxonomyArchRules.API_PROPAGATION_HAS_NO_PUBLIC_PARSERS;

    @ArchTest
    static final ArchRule otelTraceparentReaderAccessRestricted =
            ModuleTaxonomyArchRules.OTEL_TRACEPARENT_READER_ACCESS_RESTRICTED;

    @ArchTest
    static final ArchRule apiHasNoRequestIdSupportSpi =
            ModuleTaxonomyArchRules.API_HAS_NO_REQUEST_ID_SUPPORT_SPI;

    @ArchTest
    static final ArchRule apiNoServiceLoader = ModuleTaxonomyArchRules.API_NO_SERVICE_LOADER;

    @ArchTest
    static final ArchRule apiMainNoImplementationDependency =
            ModuleTaxonomyArchRules.API_MAIN_NO_IMPLEMENTATION_DEPENDENCY;

    @ArchTest
    static final ArchRule apiMainNoOtelOrFrameworkTypes =
            ModuleTaxonomyArchRules.API_MAIN_NO_OTEL_OR_FRAMEWORK_TYPES;

    @ArchTest
    static final ArchRule apiPropagationControlNoConcreteImpl =
            ModuleTaxonomyArchRules.API_PROPAGATION_CONTROL_NO_CONCRETE_IMPL;

    @ArchTest
    static final ArchRule apiMdcContractsOnly = ModuleTaxonomyArchRules.API_MDC_CONTRACTS_ONLY;

    @ArchTest
    static final ArchRule apiMainNoOtelApi = ModuleTaxonomyArchRules.API_MAIN_NO_OTEL_API;

    @ArchTest
    static final ArchRule spanFactoryApiNoTraceparentReader =
            ModuleTaxonomyArchRules.SPAN_FACTORY_API_NO_TRACEPARENT_READER;

    @ArchTest
    static final ArchRule propagationPortOwnership = ModuleTaxonomyArchRules.PROPAGATION_PORT_OWNERSHIP;

    @ArchTest
    static final ArchRule identityInternalTypesNotPublic =
            ModuleTaxonomyArchRules.IDENTITY_INTERNAL_TYPES_NOT_PUBLIC;

    @ArchTest
    static final ArchRule noApiSpanSanitizePackage =
            ModuleTaxonomyArchRules.NO_API_SPAN_SANITIZE_PACKAGE;

    @ArchTest
    static final ArchRule noPublicSpanEnrichment =
            ModuleTaxonomyArchRules.NO_PUBLIC_SPAN_ENRICHMENT;
}
