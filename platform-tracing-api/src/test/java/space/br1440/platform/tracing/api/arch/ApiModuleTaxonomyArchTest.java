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
    static final ArchRule noLegacySpanBuilderApi = ModuleTaxonomyArchRules.NO_LEGACY_SPAN_BUILDER_API;

    @ArchTest
    static final ArchRule apiPropagationHasNoPublicParsers =
            ModuleTaxonomyArchRules.API_PROPAGATION_HAS_NO_PUBLIC_PARSERS;

    @ArchTest
    static final ArchRule otelTraceparentReaderAccessRestricted =
            ModuleTaxonomyArchRules.OTEL_TRACEPARENT_READER_ACCESS_RESTRICTED;

    @ArchTest
    static final ArchRule requestIdSupportImplAccessRestricted =
            ModuleTaxonomyArchRules.REQUEST_ID_SUPPORT_IMPL_ACCESS_RESTRICTED;

    @ArchTest
    static final ArchRule apiPropagationControlNoConcreteImpl =
            ModuleTaxonomyArchRules.API_PROPAGATION_CONTROL_NO_CONCRETE_IMPL;
}
