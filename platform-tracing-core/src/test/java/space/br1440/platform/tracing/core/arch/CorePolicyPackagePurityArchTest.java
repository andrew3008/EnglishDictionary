package space.br1440.platform.tracing.core.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import space.br1440.platform.tracing.test.arch.ModuleTaxonomyArchRules;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * PR-1 forward-looking guardrail for pure policy packages in core.
 * <p>
 * {@code core.sampling} is active (PR-6B). {@code core.validation} is active (PR-9C).
 * Future {@code core.{scrubbing,enrichment}} packages are guarded the same way. Facade/span packages remain OTel-coupled (MIGRATION_RISK).
 * PR-9B adds {@code CORE_MAIN_NO_JMX}.
 */
@AnalyzeClasses(
        packages = "space.br1440.platform.tracing.core",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class CorePolicyPackagePurityArchTest {

    @ArchTest
    static final ArchRule corePolicyPackagesNoOtelOrSpring =
            ModuleTaxonomyArchRules.CORE_POLICY_PACKAGES_NO_OTEL_OR_SPRING;

    @ArchTest
    static final ArchRule coreMainNoJmx = ModuleTaxonomyArchRules.CORE_MAIN_NO_JMX;

    @ArchTest
    static final ArchRule samplingModelIsPure = ModuleTaxonomyArchRules.SAMPLING_MODEL_IS_PURE;

    @ArchTest
    static final ArchRule samplingPropertiesDependsOnlyOnModel =
            ModuleTaxonomyArchRules.SAMPLING_PROPERTIES_DEPENDS_ONLY_ON_MODEL;

    @ArchTest
    static final ArchRule samplingModelNotDependOnVersionedState =
            ModuleTaxonomyArchRules.SAMPLING_MODEL_NOT_DEPEND_ON_VERSIONED_STATE;

    @ArchTest
    static final ArchRule samplingPolicyNoEngineOrConfig =
            ModuleTaxonomyArchRules.SAMPLING_POLICY_NO_ENGINE_OR_CONFIG;

    @ArchTest
    static final ArchRule productionChainAccessRestricted =
            ModuleTaxonomyArchRules.PRODUCTION_CHAIN_ACCESS_RESTRICTED;

    @ArchTest
    static final ArchRule samplingRuleImplsOnlyInPolicy =
            ModuleTaxonomyArchRules.SAMPLING_RULE_IMPLS_ONLY_IN_POLICY;

    @ArchTest
    static final ArchRule coreEnrichmentNoManualOrLegacy =
            ModuleTaxonomyArchRules.CORE_ENRICHMENT_NO_MANUAL_OR_LEGACY;

    @ArchTest
    static final ArchRule coreNamingNoOtelTraceContext =
            ModuleTaxonomyArchRules.CORE_NAMING_NO_OTEL_TRACE_CONTEXT;

    @ArchTest
    static final ArchRule coreSemconvPolicyOtelCommonOnly =
            ModuleTaxonomyArchRules.CORE_SEMCONV_POLICY_OTEL_COMMON_ONLY;

    @ArchTest
    static final ArchRule urlSanitizerOnlyInCoreManual =
            ModuleTaxonomyArchRules.URL_SANITIZER_ONLY_IN_CORE_MANUAL;

    @ArchTest
    static final ArchRule noSqlSanitizer =
            ModuleTaxonomyArchRules.NO_SQL_SANITIZER;

    @ArchTest
    static final ArchRule noPlatformSpanContextKeys =
            ModuleTaxonomyArchRules.NO_PLATFORM_SPAN_CONTEXT_KEYS;

    @ArchTest
    static final ArchRule noLegacySpanBuilderApi =
            ModuleTaxonomyArchRules.NO_LEGACY_SPAN_BUILDER_API;

    @ArchTest
    static final ArchRule otelTraceparentReaderAccessRestricted =
            ModuleTaxonomyArchRules.OTEL_TRACEPARENT_READER_ACCESS_RESTRICTED;

    @ArchTest
    static final ArchRule requestIdSupportImplAccessRestricted =
            ModuleTaxonomyArchRules.REQUEST_ID_SUPPORT_IMPL_ACCESS_RESTRICTED;

    // PR-3 propagation.control guardrails (core-classpath scope).
    // API_PROPAGATION_CONTROL_NO_CONCRETE_IMPL намеренно НЕ здесь: правило проверяет
    // ..api.propagation.control.. и зарегистрировано в platform-tracing-api
    // (ApiModuleTaxonomyArchTest), т.к. @AnalyzeClasses этого теста импортирует только core.
    @ArchTest
    static final ArchRule controlImplsOnlyInCore =
            ModuleTaxonomyArchRules.CONTROL_IMPLS_ONLY_IN_CORE;

    @ArchTest
    static final ArchRule controlImplAccessRestricted =
            ModuleTaxonomyArchRules.CONTROL_IMPL_ACCESS_RESTRICTED;

    @ArchTest
    static final ArchRule rootPackageMustBeEmpty =
            noClasses()
                    .should().resideInAPackage("space.br1440.platform.tracing.core")
                    .because("root package space.br1440.platform.tracing.core должен быть пустым после PR-3");

    @ArchTest
    static final ArchRule noImplPackages =
            noClasses()
                    .should().resideInAnyPackage("..impl..")
                    .because("пакет core.impl удалён");
}
